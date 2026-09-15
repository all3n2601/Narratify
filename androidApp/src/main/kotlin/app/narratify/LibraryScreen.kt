package app.narratify

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.view.animation.DecelerateInterpolator

class LibraryScreen(
    context: Context,
    private val localBooks: List<LocalBook> = emptyList(),
    private val hiddenBooks: List<LocalBook> = emptyList(),
    private val onImport: () -> Unit = {},
    private val onOpenLocal: (LocalBook) -> Unit = {},
    /** Opens a book and starts narration straight away, for the player column's play button. */
    private val onPlayLocal: (LocalBook) -> Unit = onOpenLocal,
    private val onSetHiddenLocal: (LocalBook, Boolean) -> Unit = { _, _ -> },
    private val onRemoveLocal: (LocalBook) -> Unit = {},
    private val onAddNarration: (LocalBook) -> Unit = {},
    private val onRemoveNarration: (LocalBook) -> Unit = {},
    private val onOpenChapters: (LocalBook) -> Unit = {},
    private val initialError: String? = null,
    private val onNavigate: (AppSection) -> Unit = {},
    private val preferences: AppPreferences = AppPreferences(context),
) : FrameLayout(context) {
    private val tablet = resources.configuration.smallestScreenWidthDp >= 600
    private val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** A landscape tablet has room for a third column, so the player lives there instead of on top. */
    private val wide = tablet && resources.configuration.screenWidthDp >= WIDE_LAYOUT_DP
    private val theme = enchantedLibraryPalette()
    private val ink get() = theme.ink
    private val mutedInk get() = theme.muted
    private val animatedViews = mutableListOf<View>()
    private val ambientAnimators = mutableListOf<ValueAnimator>()
    private val bodyHost = LinearLayout(context)
    private val controlsHost = LinearLayout(context)
    private val playerHost = LinearLayout(context)
    private val forest = EnchantedForestView(context)
    private val gutter = if (tablet) Gap.XXL else Gap.LG
    private var query = ""
    private var shelfFilter = ShelfFilter.ALL
    private var playerQueueIndex = 0

    init {
        setBackgroundColor(theme.canvas)
        addView(
            forest,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            if (tablet) tabletLayout() else phoneLayout(),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        applySafeArea()
        post {
            playEntrance()
            initialError?.let(::showError)
        }
    }

    private fun phoneLayout() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(content(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(phoneNavigation(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(78)))
    }

    private fun tabletLayout() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(tabletRail(), LinearLayout.LayoutParams(dp(96), LayoutParams.MATCH_PARENT))
        addView(context.ruleView(theme.rule), LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
        addView(content(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        if (wide) {
            addView(context.ruleView(theme.rule), LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT))
            addView(playerColumn(), LinearLayout.LayoutParams(dp(PLAYER_COLUMN_DP), LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * The third column. In landscape the "continue reading" band used to run the full width of the
     * page and push the shelf below the fold; here it becomes a standing player next to the shelf.
     */
    private fun playerColumn() = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        setPadding(dp(Gap.LG), dp(Gap.XL), dp(Gap.LG), dp(Gap.XL))
        playerHost.orientation = LinearLayout.VERTICAL
        addView(playerHost)
        renderPlayer()
    }

    private fun content() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(gutter), dp(if (tablet) Gap.XL else Gap.LG), dp(gutter), 0)
        addView(header())
        addView(ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setOnScrollChangeListener { _, _, scrollY, _, _ -> forest.setScrollOffset(scrollY) }
            bodyHost.orientation = LinearLayout.VERTICAL
            addView(bodyHost)
            renderLibraryBody()
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        forest.observeTouch(event, -forest.left.toFloat(), -forest.top.toFloat())
        return super.dispatchTouchEvent(event)
    }

    /**
     * The greeting keeps the atmospheric shell personal while the large editorial title anchors
     * the screen. The small sigil is code-drawn, so it stays crisp above the moving forest.
     */
    private fun header() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                if (tablet) {
                    addView(context.eyebrow(timeGreeting(), theme.accent))
                } else {
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(context.brandMarkView(36), LinearLayout.LayoutParams(dp(36), dp(36)))
                        addView(
                            context.eyebrow("Narratify", theme.accent),
                            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(Gap.XS)
                            },
                        )
                    })
                }
                addView(
                    context.styledText(
                        "Your library",
                        if (tablet) Type.DISPLAY_LARGE else Type.DISPLAY,
                        ink,
                        Typeface.BOLD,
                        serif = true,
                    ),
                    stack(Gap.XXS),
                )
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(iconAction(NarratifyIcon.Glyph.PLUS, "Import a local book") { onImport() })
        }
        addView(top)
        animatedViews += top
        addView(searchField(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(Gap.MD)
        })
    }

    /**
     * A ruled field rather than a floating pill: the icon sits inline, the rule under the header
     * closes it off, and nothing casts a shadow.
     */
    private fun searchField() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Gap.MD), 0, dp(Gap.MD), 0)
        background = glassShape(0xD914332D.toInt(), 0xB80B241F.toInt(), Radius.INPUT, theme.outline)
        addView(context.iconView(NarratifyIcon.Glyph.SEARCH, mutedInk, sizeDp = 18))
        addView(EditText(context).apply {
            background = null
            setPadding(dp(Gap.SM), 0, 0, 0)
            hint = "Search your stories"
            textSize = Type.BODY
            typeface = Type.sans()
            setSingleLine(true)
            setTextColor(ink)
            setHintTextColor(mutedInk)
            includeFontPadding = false
            contentDescription = "Search your local library"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty().trim()
                    renderLibraryBody()
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun renderLibraryControls() {
        controlsHost.removeAllViews()
        controlsHost.gravity = Gravity.CENTER_VERTICAL
        ShelfFilter.entries.forEach { filter ->
            controlsHost.addView(controlChip(filter), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply {
                marginEnd = dp(Gap.XS)
            })
        }
    }

    private fun libraryControls() = controlsHost.apply {
        // The shelf body is rebuilt when a filter changes. Detach this persistent control row
        // before moving it into the newly built body.
        (parent as? android.view.ViewGroup)?.removeView(this)
        gravity = Gravity.CENTER_VERTICAL
        renderLibraryControls()
    }

    private fun controlChip(filter: ShelfFilter): TextView {
        val selected = shelfFilter == filter
        val chipLabel = if (filter == ShelfFilter.HIDDEN && hiddenBooks.isNotEmpty()) {
            "Hidden ${hiddenBooks.size}"
        } else filter.label
        return context.styledText(
            chipLabel,
            Type.LABEL,
            if (selected) theme.onAccent else ink,
            if (selected) Typeface.BOLD else Typeface.NORMAL,
            tracking = Type.TRACKING_LABEL,
        ).apply {
            gravity = Gravity.CENTER
            background = if (selected) {
                glassShape(theme.accent, 0xFF82BE94.toInt(), Radius.PILL, 0xCCDAF4DB.toInt())
            } else {
                glassShape(0xA312302A.toInt(), 0x8F0A241F.toInt(), Radius.PILL, theme.outline)
            }
            setPadding(dp(Gap.MD), 0, dp(Gap.MD), 0)
            isClickable = true
            isFocusable = true
            contentDescription = if (selected) "${filter.label}, selected" else "Show ${filter.label.lowercase()} books"
            setOnClickListener {
                if (!selected) {
                    shelfFilter = filter
                    renderLibraryControls()
                    renderLibraryBody()
                }
            }
        }
    }

    private fun renderLibraryBody() {
        ambientAnimators.forEach(ValueAnimator::cancel)
        ambientAnimators.clear()
        bodyHost.removeAllViews()
        bodyHost.addView(libraryBody())
    }

    private fun libraryBody() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(Gap.XXL))
        val normalizedQuery = query.lowercase()
        val shelfBooks = if (shelfFilter == ShelfFilter.HIDDEN) hiddenBooks else localBooks
        val ordered = if (preferences.sortNewestFirst) shelfBooks else shelfBooks.sortedBy { it.title.lowercase() }
        val imported = ordered.filter { book ->
            when (shelfFilter) {
                ShelfFilter.ALL -> true
                ShelfFilter.READING -> book.format !in AUDIO_FORMATS
                ShelfFilter.AUDIO -> book.format in AUDIO_FORMATS
                ShelfFilter.HIDDEN -> true
            }
        }.map(::coverArtFor).filter {
            normalizedQuery.isBlank() ||
                it.title.lowercase().contains(normalizedQuery) ||
                it.author.lowercase().contains(normalizedQuery)
        }
        if (!wide && shelfFilter == ShelfFilter.ALL && query.isBlank() && imported.isNotEmpty()) {
            // Capped measure: stretched edge-to-edge on a landscape tablet the feature block
            // became a black band with a paragraph stranded in the middle of it.
            addView(
                continueCard(imported.first()),
                LinearLayout.LayoutParams(
                    if (tablet) dp(720) else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(Gap.XS) },
            )
        }
        if (localBooks.isNotEmpty() || hiddenBooks.isNotEmpty()) {
            val detail = buildString {
                append("${localBooks.size} visible")
                if (hiddenBooks.isNotEmpty()) append(" · ${hiddenBooks.size} hidden")
            }
            addView(sectionHeader("Your collection", detail), stack(Gap.XXL))
            addView(libraryControls(), stack(Gap.SM))
            if (imported.isNotEmpty()) {
                addView(bookGrid(imported), stack(Gap.MD))
            } else {
                val message = when {
                    query.isNotBlank() -> "Nothing in ${shelfFilter.label.lowercase()} matches “$query”."
                    shelfFilter == ShelfFilter.HIDDEN -> "No hidden books. Use a book’s menu to hide it from your main shelf."
                    shelfFilter == ShelfFilter.AUDIO -> "No audiobooks yet. Import an MP3, M4A, or M4B to see it here."
                    shelfFilter == ShelfFilter.ALL && localBooks.isEmpty() -> "Your visible shelf is empty. Your hidden books are still safe in Hidden."
                    else -> "No reading books yet. Import an EPUB or text file to see it here."
                }
                addView(
                    context.styledText(message, Type.BODY, mutedInk).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(Gap.LG), dp(Gap.XXL), dp(Gap.LG), dp(Gap.XXL))
                    },
                    stack(Gap.SM),
                )
            }
        } else {
            addView(emptyLibraryCard(), stack(Gap.XL))
        }
    }

    private fun emptyLibraryCard() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Gap.XL), dp(Gap.XL), dp(Gap.XL), dp(Gap.XL))
        background = glassShape(0xE012302A.toInt(), 0xD108211D.toInt(), Radius.CARD, theme.outline)
        addView(context.eyebrow("Empty shelf", theme.accent))
        addView(
            context.styledText("Nothing here yet", Type.HEADING, ink, Typeface.BOLD, serif = true),
            stack(Gap.XS),
        )
        addView(
            context.styledText(
                "Import an EPUB, a text file, or an audiobook. Narratify copies it into private storage so it stays readable offline.",
                Type.BODY,
                mutedInk,
            ).apply { setLineSpacing(0f, 1.45f) },
            stack(Gap.XS),
        )
        addView(context.primaryButton(theme, "Import a book") { onImport() }, stack(Gap.LG).apply {
            width = LayoutParams.WRAP_CONTENT
        })
    }

    /**
     * The one inverted block on the screen. It now follows the theme through [AppPalette.feature]
     * instead of painting itself dark inside a cream page.
     */
    private fun continueCard(book: CoverArt) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(Gap.MD), dp(Gap.MD), dp(Gap.MD), dp(Gap.MD))
        background = glassShape(0xF1123931.toInt(), 0xE507241F.toInt(), Radius.SHEET, theme.outline)
        elevation = dp(8).toFloat()
        clipToOutline = true
        isClickable = true
        isFocusable = true
        contentDescription = "Continue ${book.title}, ${book.progress} percent complete"
        setOnClickListener { open(book) }
        addView(BookCoverView(context, book).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(if (tablet) 88 else 70), dp(if (tablet) 132 else 104)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Gap.MD), 0, dp(Gap.SM), 0)
            addView(context.eyebrow("Continue reading", theme.featureMuted))
            addView(
                context.styledText(
                    book.title,
                    if (tablet) Type.HEADING else Type.TITLE,
                    theme.onFeature,
                    Typeface.BOLD,
                    serif = true,
                ).apply { maxLines = 2 },
                stack(Gap.XS),
            )
            addView(
                context.styledText(book.author, Type.LABEL, theme.featureMuted).apply { maxLines = 1 },
                stack(Gap.XXS),
            )
            addView(ProgressRule(context, book.progress, theme.accent, theme.featureMuted), stack(Gap.SM).apply {
                height = dp(3)
            })
            addView(
                context.styledText("${book.progress}% complete", Type.MICRO, theme.featureMuted),
                stack(Gap.XS),
            )
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val playButton = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = glassShape(theme.accent, 0xFF77BC8C.toInt(), Radius.PILL, 0xE6E3F6DE.toInt())
            addView(context.iconView(NarratifyIcon.Glyph.PLAY, theme.onAccent, sizeDp = 20))
            isClickable = true
            isFocusable = true
            contentDescription = "Open ${book.title}"
            setOnClickListener { open(book) }
        }
        addView(playButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        startBreathingGlow(playButton)
        animatedViews += this
        startGentleFloat(this)
    }

    /** Serif heading, sans count, hairline underneath. Straight out of a contents page. */
    private fun sectionHeader(title: String, detail: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            gravity = Gravity.BOTTOM
            addView(
                context.styledText(title, Type.HEADING, ink, Typeface.BOLD, serif = true),
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                context.styledText(detail.uppercase(), Type.MICRO, mutedInk, tracking = Type.TRACKING_EYEBROW).apply {
                    setPadding(0, 0, 0, dp(Gap.XXS))
                },
            )
        })
        addView(context.ruleView(theme.rule, topMarginDp = Gap.XS))
    }

    /**
     * Columns come from the width that is actually available, not from a fixed count. A fixed
     * count meant a landscape tablet divided its whole width by four and drew covers tall enough
     * to run off the screen.
     */
    private fun bookGrid(books: List<CoverArt>): View {
        val availableDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density -
            (if (tablet) 96f else 0f) - (if (wide) PLAYER_COLUMN_DP.toFloat() else 0f) - gutter * 2f
        val targetCoverDp = when {
            tablet && preferences.compactLibrary -> 116f
            tablet -> 152f
            preferences.compactLibrary -> 126f
            else -> 156f
        }
        val columns = ((availableDp + Gap.MD) / (targetCoverDp + Gap.MD)).toInt().coerceIn(2, 8)
        if (landscape) return bookshelf(books, availableDp, targetCoverDp, columns)
        return GridLayout(context).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            books.forEachIndexed { index, art ->
                val tile = BookTile(
                    context = context,
                    art = art,
                    theme = theme,
                    action = { open(art) },
                    menuAction = { anchor -> showBookMenu(art, anchor) },
                )
                addView(tile, GridLayout.LayoutParams().apply {
                    width = 0
                    // The cover derives its own height from the column width, so the row wraps
                    // to whatever a real book trim needs instead of a guessed fixed height.
                    height = LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, 0, dp(Gap.MD), dp(Gap.XL))
                })
                tile.tag = index
                animatedViews += tile
            }
            repeat((columns - books.size % columns) % columns) {
                addView(Space(context), GridLayout.LayoutParams().apply {
                    width = 0
                    height = 1
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, 0, dp(Gap.MD), 0)
                })
            }
        }
    }

    /** Cover-forward landscape layout: fixed-size books stand directly on walnut shelf planks. */
    private fun bookshelf(books: List<CoverArt>, availableDp: Float, targetCoverDp: Float, suggestedColumns: Int): View {
        val bookWidthDp = targetCoverDp.coerceIn(104f, 132f).toInt()
        val columns = minOf(
            suggestedColumns,
            ((availableDp + Gap.MD) / (bookWidthDp + Gap.MD)).toInt().coerceAtLeast(2),
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            books.chunked(columns).forEachIndexed { rowIndex, rowBooks ->
                addView(LinearLayout(context).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    rowBooks.forEachIndexed { columnIndex, art ->
                        val tile = BookTile(
                            context = context,
                            art = art,
                            theme = theme,
                            action = { open(art) },
                            menuAction = { anchor -> showBookMenu(art, anchor) },
                            showMetadata = false,
                        )
                        addView(tile, LinearLayout.LayoutParams(dp(bookWidthDp), LayoutParams.WRAP_CONTENT).apply {
                            if (columnIndex > 0) marginStart = dp(Gap.MD)
                        })
                        tile.tag = rowIndex * columns + columnIndex
                        animatedViews += tile
                    }
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(
                    BookshelfPlankView(context, rowIndex),
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30)),
                )
                if (rowIndex < books.lastIndex / columns) addView(Space(context), LinearLayout.LayoutParams(1, dp(Gap.LG)))
            }
        }
    }

    private val narrationObserver: (ReaderNarration.Session?) -> Unit = { post { renderPlayer() } }
    private val epubNarrationObserver: (EpubNarration.Session?) -> Unit = { post { renderPlayer() } }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (wide) {
            ReaderNarration.observe(narrationObserver)
            EpubNarration.observe(epubNarrationObserver)
        }
    }

    override fun onDetachedFromWindow() {
        if (wide) {
            ReaderNarration.stopObserving(narrationObserver)
            EpubNarration.stopObserving(epubNarrationObserver)
        }
        ambientAnimators.forEach(ValueAnimator::cancel)
        ambientAnimators.clear()
        super.onDetachedFromWindow()
    }

    private fun renderPlayer() {
        if (!wide) return
        playerHost.removeAllViews()
        val playing = MiniPlayer.resolve(ReaderNarration.session(), EpubNarration.session(), audiobook = null)
        val reading = playing?.let { active -> localBooks.firstOrNull { it.id == active.id } }?.let(::coverArtFor)
        val queue = localBooks.map(::coverArtFor)
        playerQueueIndex = playerQueueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val next = queue.getOrNull(playerQueueIndex)
        when {
            playing != null && reading != null -> playerHost.addView(nowReadingPanel(reading, playing))
            next != null -> playerHost.addView(upNextPanel(next, playerQueueIndex, queue.size))
            else -> playerHost.addView(
                context.styledText("Nothing to play yet.", Type.BODY, mutedInk).apply { gravity = Gravity.CENTER },
            )
        }
    }

    /** The standing player: cover, what is being read, and transport that acts on the session. */
    private fun nowReadingPanel(book: CoverArt, session: MiniPlayer.State) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.eyebrow("Now reading", theme.accent))
        addView(playerArtwork(book), stack(Gap.MD))
        addView(
            context.styledText(book.title, Type.TITLE, ink, Typeface.BOLD, serif = true).apply { maxLines = 3 },
            stack(Gap.MD),
        )
        addView(context.styledText(session.subtitle, Type.MICRO, mutedInk).apply { maxLines = 2 }, stack(Gap.XXS))
        addView(playerProgress(book), stack(Gap.MD))
        addView(transportRow(book, session), stack(Gap.LG))
        addView(speedRow(), stack(Gap.MD))
        addView(playerActions(book, session), stack(Gap.LG))
    }

    private fun upNextPanel(book: CoverArt, index: Int, queueSize: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(context.eyebrow("Continue reading · ${index + 1} of $queueSize", theme.accent))
        addView(playerArtwork(book), stack(Gap.MD))
        addView(
            context.styledText(book.title, Type.TITLE, ink, Typeface.BOLD, serif = true).apply { maxLines = 3 },
            stack(Gap.MD),
        )
        addView(context.styledText(book.author, Type.LABEL, mutedInk).apply { maxLines = 1 }, stack(Gap.XXS))
        addView(playerProgress(book), stack(Gap.MD))
        addView(idleTransportRow(book, index, queueSize), stack(Gap.LG))
        addView(playerActions(book, session = null), stack(Gap.LG))
    }

    private fun playerArtwork(book: CoverArt) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(
            BookCoverView(context, book).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(168), dp(252)),
        )
    }

    private fun playerProgress(book: CoverArt) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            ProgressRule(context, book.progress, theme.accent, theme.outline),
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(4)),
        )
        addView(LinearLayout(context).apply {
            addView(
                context.styledText("${book.progress}% complete", Type.MICRO, mutedInk),
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                context.styledText("${(100 - book.progress).coerceAtLeast(0)}% left", Type.MICRO, mutedInk).apply {
                    gravity = Gravity.END
                },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }, stack(Gap.XS))
    }

    private fun transportRow(book: CoverArt, session: MiniPlayer.State) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        val playing = session.playing
        val queue = localBooks.map(::coverArtFor)
        val current = queue.indexOfFirst { it.id == book.id }
        val previous = queue.getOrNull(current - 1)
        val next = queue.getOrNull(current + 1)
        addView(
            queueButton(NarratifyIcon.Glyph.PREVIOUS, previous, "Previous book") {
                previous?.let(::play)
            },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        addView(
            circleButton(
                if (playing) NarratifyIcon.Glyph.PAUSE else NarratifyIcon.Glyph.PLAY,
                if (playing) "Pause read aloud" else "Resume read aloud",
                primary = true,
            ) {
                togglePlayback(session)
                renderPlayer()
            },
            LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(Gap.XL); marginEnd = dp(Gap.XL) },
        )
        addView(
            queueButton(NarratifyIcon.Glyph.NEXT, next, "Next book") { next?.let(::play) },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
    }

    private fun idleTransportRow(book: CoverArt, index: Int, queueSize: Int) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        val previous = localBooks.getOrNull(index - 1)?.let(::coverArtFor)
        val next = localBooks.getOrNull(index + 1)?.let(::coverArtFor)
        addView(
            queueButton(NarratifyIcon.Glyph.PREVIOUS, previous, "Previous book") { selectPlayerBook(-1) },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        addView(
            circleButton(NarratifyIcon.Glyph.PLAY, "Read ${book.title} aloud", primary = true) { play(book) },
            LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginStart = dp(Gap.XL); marginEnd = dp(Gap.XL) },
        )
        addView(
            queueButton(NarratifyIcon.Glyph.NEXT, next, "Next book") { selectPlayerBook(1) },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        if (queueSize <= 1) alpha = .96f
    }

    private fun queueButton(
        glyph: NarratifyIcon.Glyph,
        destination: CoverArt?,
        label: String,
        action: () -> Unit,
    ) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(context.iconView(glyph, if (destination == null) theme.muted else ink, sizeDp = 24))
        isEnabled = destination != null
        isClickable = destination != null
        isFocusable = destination != null
        alpha = if (destination == null) .34f else 1f
        contentDescription = destination?.let { "$label: ${it.title}" } ?: "$label unavailable"
        setOnClickListener { if (destination != null) action() }
    }

    private fun selectPlayerBook(delta: Int) {
        playerQueueIndex = (playerQueueIndex + delta).coerceIn(0, (localBooks.size - 1).coerceAtLeast(0))
        renderPlayer()
    }

    private fun playerActions(book: CoverArt, session: MiniPlayer.State?) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        addView(
            playerAction("Open book", primary = true) { open(book) },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        if (session != null) {
            addView(
                playerAction("End session", primary = false) { stopPlayback(session); renderPlayer() },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(Gap.XS) },
            )
        }
    }

    private fun playerAction(title: String, primary: Boolean, action: () -> Unit) =
        context.styledText(title, Type.LABEL, if (primary) theme.onAccent else ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = if (primary) {
                surfaceShape(theme.accent, Radius.PILL)
            } else {
                glassShape(0xA312302A.toInt(), 0x8F0A241F.toInt(), Radius.PILL, theme.outline)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun togglePlayback(session: MiniPlayer.State) {
        when (session.source) {
            MiniPlayer.Source.NARRATION ->
                ReaderNarration.controller()?.let { if (session.playing) it.pause() else it.resume() }

            MiniPlayer.Source.EPUB -> if (session.playing) EpubNarration.pause() else EpubNarration.play()
            MiniPlayer.Source.AUDIOBOOK -> Unit
        }
    }

    private fun stopPlayback(session: MiniPlayer.State) {
        when (session.source) {
            MiniPlayer.Source.NARRATION -> ReaderNarration.controller()?.stop()
            MiniPlayer.Source.EPUB -> EpubNarration.stop()
            MiniPlayer.Source.AUDIOBOOK -> Unit
        }
    }

    private fun speedRow() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val label = context.styledText(formatSpeed(preferences.speechRate), Type.LABEL, ink, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        addView(
            context.touchTarget(context.styledText("−", Type.BODY_LARGE, theme.accent, Typeface.BOLD), "Slower") {
                changeSpeed(-.1f, label)
            },
            LinearLayout.LayoutParams(dp(48), dp(44)),
        )
        addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(
            context.touchTarget(context.styledText("+", Type.BODY_LARGE, theme.accent, Typeface.BOLD), "Faster") {
                changeSpeed(.1f, label)
            },
            LinearLayout.LayoutParams(dp(48), dp(44)),
        )
    }

    private fun changeSpeed(delta: Float, label: TextView) {
        val speed = (preferences.speechRate + delta).coerceIn(.5f, 2f)
        preferences.speechRate = speed
        label.text = formatSpeed(speed)
        ReaderNarration.controller()?.setSpeed(speed)
        EpubNarration.setSpeed(speed.toDouble())
    }

    private fun formatSpeed(value: Float) = if (value % 1f == 0f) {
        String.format(java.util.Locale.US, "%.0f×", value)
    } else {
        String.format(java.util.Locale.US, "%.1f×", value)
    }

    private fun circleButton(
        glyph: NarratifyIcon.Glyph,
        description: String,
        primary: Boolean,
        action: () -> Unit,
    ) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        background = surfaceShape(if (primary) theme.accent else theme.subtle, Radius.PILL)
        addView(context.iconView(glyph, if (primary) theme.onAccent else ink, sizeDp = if (primary) 26 else 20))
        isClickable = true
        isFocusable = true
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun play(book: CoverArt) {
        (localBooks + hiddenBooks).firstOrNull { it.id == book.id }?.let(onPlayLocal)
    }

    private fun open(book: CoverArt) {
        (localBooks + hiddenBooks).firstOrNull { it.id == book.id }?.let(onOpenLocal)
    }

    private fun showBookMenu(art: CoverArt, anchor: View) {
        val book = (localBooks + hiddenBooks).firstOrNull { it.id == art.id } ?: return
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Gap.LG), dp(Gap.LG), dp(Gap.LG), dp(Gap.MD))
            background = glassShape(0xF414332D.toInt(), 0xF008211D.toInt(), Radius.SHEET, theme.outline)
            addView(context.eyebrow("Book options", theme.accent))
            addView(
                context.styledText(book.title, Type.TITLE, ink, Typeface.BOLD, serif = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                stack(Gap.XS),
            )
            addView(context.bookActionRow(
                theme,
                title = if (book.hidden) "Unhide" else "Hide",
                detail = if (book.hidden) "Return this book to your main shelf" else "Move this book to the Hidden shelf",
                color = ink,
            ) {
                dialog.dismiss()
                onSetHiddenLocal(book, !book.hidden)
            }, stack(Gap.MD))
            if (book.hasNarration) {
                addView(context.ruleView(theme.rule))
                addView(context.bookActionRow(
                    theme,
                    title = "Chapters",
                    detail = "Jump between the audio and the text",
                    color = ink,
                ) {
                    dialog.dismiss()
                    onOpenChapters(book)
                })
            }
            addView(context.ruleView(theme.rule))
            addView(context.bookActionRow(
                theme,
                title = if (book.hasNarration) "Replace narration" else "Add narration",
                detail = if (book.hasNarration) {
                    "Swap the audiobook paired with this book"
                } else {
                    "Pair an MP3, M4A, or M4B so you can move between reading and listening"
                },
                color = ink,
            ) {
                dialog.dismiss()
                onAddNarration(book)
            })
            if (book.hasNarration) {
                addView(context.ruleView(theme.rule))
                addView(context.bookActionRow(
                    theme,
                    title = "Remove narration",
                    detail = "Delete the paired audio and its chapter list",
                    color = theme.danger,
                ) {
                    dialog.dismiss()
                    onRemoveNarration(book)
                })
            }
            addView(context.ruleView(theme.rule))
            addView(context.bookActionRow(
                theme,
                title = "Remove from library",
                detail = "Permanently delete Narratify’s saved copy",
                color = theme.danger,
            ) {
                dialog.dismiss()
                confirmRemoval(book)
            })
        })
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                if (tablet) dp(400) else resources.displayMetrics.widthPixels - dp(Gap.SECTION),
                LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun confirmRemoval(book: LocalBook) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Gap.XL), dp(Gap.XL), dp(Gap.XL), dp(Gap.LG))
            background = surfaceShape(theme.surface, Radius.SHEET, theme.outline)
            addView(context.eyebrow("Remove book", theme.danger))
            addView(
                context.styledText("Remove “${book.title}”?", Type.TITLE, ink, Typeface.BOLD, serif = true),
                stack(Gap.XS),
            )
            addView(
                context.styledText(
                    "This permanently deletes Narratify’s saved copy and its reading progress. This can’t be undone.",
                    Type.BODY,
                    mutedInk,
                ).apply { setLineSpacing(0f, 1.4f) },
                stack(Gap.XS),
            )
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(context.styledText("Cancel", Type.LABEL, ink, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(Gap.LG), 0, dp(Gap.LG), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
                addView(context.styledText("Remove", Type.LABEL, Color.WHITE, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER
                    background = surfaceShape(theme.danger, Radius.PILL)
                    setPadding(dp(Gap.LG), 0, dp(Gap.LG), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onRemoveLocal(book)
                    }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply {
                    marginStart = dp(Gap.XS)
                })
            }, stack(Gap.LG))
        })
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                if (tablet) dp(440) else resources.displayMetrics.widthPixels - dp(Gap.SECTION),
                LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /** A floating glass navigation shelf leaves the forest visible around its silhouette. */
    private fun phoneNavigation() = FrameLayout(context).apply {
        setPadding(dp(Gap.SM), dp(Gap.XS), dp(Gap.SM), dp(Gap.XS))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = glassShape(0xF012302A.toInt(), 0xEC081E1A.toInt(), Radius.SHEET, theme.outline)
            elevation = dp(10).toFloat()
            AppSection.entries.forEach { section ->
                addView(navItem(section, section == AppSection.LIBRARY), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun tabletRail() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(Gap.XS), dp(Gap.XL), dp(Gap.XS), dp(Gap.MD))
        background = glassShape(0xED102D28.toInt(), 0xE8071D19.toInt(), Radius.NONE, theme.outline)
        addView(context.brandMarkView(44), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(Space(context), LinearLayout.LayoutParams(1, dp(Gap.XXL)))
        listOf(AppSection.LIBRARY, AppSection.DISCOVER, AppSection.VOICES).forEach {
            addView(navItem(it, it == AppSection.LIBRARY), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        }
        addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f))
        addView(navItem(AppSection.SETTINGS, false), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
    }

    private fun navItem(section: AppSection, selected: Boolean) = buildNavItem(
        context, theme, section, selected,
    ) { onNavigate(section) }

    private fun iconAction(glyph: NarratifyIcon.Glyph, description: String, action: () -> Unit) =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = glassShape(0xB51B453B.toInt(), 0x99102F28.toInt(), Radius.PILL, theme.outline)
            addView(context.iconView(glyph, theme.accent, sizeDp = 20))
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun showError(message: String) {
        val notice = Dialog(context)
        notice.requestWindowFeature(Window.FEATURE_NO_TITLE)
        notice.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Gap.XL), dp(Gap.XL), dp(Gap.XL), dp(Gap.LG))
            background = surfaceShape(theme.surface, Radius.SHEET, theme.outline)
            addView(context.eyebrow("Import failed", theme.danger))
            addView(
                context.styledText("Couldn’t import that book", Type.TITLE, ink, Typeface.BOLD, serif = true),
                stack(Gap.XS),
            )
            addView(
                context.styledText(message, Type.BODY, mutedInk).apply { setLineSpacing(0f, 1.4f) },
                stack(Gap.XS),
            )
            addView(context.primaryButton(theme, "Got it") { notice.dismiss() }, stack(Gap.LG))
        })
        notice.setOnShowListener {
            notice.window?.setLayout(
                if (tablet) dp(420) else resources.displayMetrics.widthPixels - dp(Gap.SECTION),
                LayoutParams.WRAP_CONTENT,
            )
        }
        notice.show()
        notice.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /** Content settles in; nothing bounces. Editorial layouts do not overshoot. */
    private fun playEntrance() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animatedViews.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(12).toFloat()
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay((index * 35L).coerceAtMost(280L)).setDuration(260)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun startGentleFloat(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        ambientAnimators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5_600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.translationY = -dp(3) * (animatedValue as Float) }
            start()
        }
    }

    private fun startBreathingGlow(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        ambientAnimators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val phase = animatedValue as Float
                val scale = 1f + phase * .045f
                view.scaleX = scale
                view.scaleY = scale
                view.elevation = dp(5 + (phase * 5).toInt()).toFloat()
            }
            start()
        }
    }

    private fun applySafeArea() {
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun stack(top: Int) =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun timeGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private companion object {
        const val WIDE_LAYOUT_DP = 840
        const val PLAYER_COLUMN_DP = 320
        val AUDIO_FORMATS = setOf("MP3", "M4A", "M4B")
    }

    private enum class ShelfFilter(val label: String) {
        ALL("All"), READING("Reading"), AUDIO("Audio"), HIDDEN("Hidden")
    }
}

/**
 * Shared by the library and every destination so the bar looks identical wherever it appears.
 * The active item is marked by a short accent rule above the icon — a printed tab, not a blob.
 */
internal fun buildNavItem(
    context: Context,
    theme: AppPalette,
    section: AppSection,
    selected: Boolean,
    onSelect: () -> Unit,
): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val tint = if (selected) theme.accent else theme.muted
    val glyph = when (section) {
        AppSection.LIBRARY -> NarratifyIcon.Glyph.LIBRARY
        AppSection.DISCOVER -> NarratifyIcon.Glyph.SEARCH
        AppSection.VOICES -> NarratifyIcon.Glyph.VOICES
        AppSection.SETTINGS -> NarratifyIcon.Glyph.SETTINGS
    }
    val title = section.name.lowercase().replaceFirstChar(Char::uppercase)
    addView(View(context).apply {
        setBackgroundColor(if (selected) theme.accent else Color.TRANSPARENT)
    }, LinearLayout.LayoutParams(dp(18), dp(2)).apply { bottomMargin = dp(Gap.XS) })
    addView(context.iconView(glyph, tint, sizeDp = 21))
    addView(
        context.styledText(title, Type.MICRO, tint, if (selected) Typeface.BOLD else Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(Gap.XXS)
        },
    )
    contentDescription = if (selected) "$title, selected" else title
    isClickable = !selected
    isFocusable = true
    setOnClickListener { if (!selected) onSelect() }
}

@SuppressLint("ViewConstructor")
private class BookTile(
    context: Context,
    art: CoverArt,
    theme: AppPalette,
    action: () -> Unit,
    menuAction: (View) -> Unit,
    showMetadata: Boolean = true,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        isClickable = true
        isFocusable = true
        contentDescription = "Open ${art.title} by ${art.author}, ${art.progress} percent complete"
        val cover = BookCoverView(context, art).apply {
            cameraDistance = dp(1_200).toFloat()
        }
        cover.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(FrameLayout(context).apply {
            addView(cover, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(context.styledText("⋮", Type.BODY_LARGE, theme.ink, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = glassShape(0xD917342E.toInt(), 0xC00A211D.toInt(), Radius.PILL, theme.outline)
                isClickable = true
                isFocusable = true
                contentDescription = "More options for ${art.title}"
                setOnClickListener { menuAction(this) }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(Gap.XS)
                marginEnd = dp(Gap.XS)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (showMetadata) {
            addView(
                label(art.title, Type.LABEL, theme.ink, Typeface.BOLD, serif = true),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Gap.XS) },
            )
            addView(
                label(art.author, Type.MICRO, theme.muted),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) },
            )
            // Progress reads as a rule under the shelf label rather than a coloured bar on the cover.
            addView(
                ProgressRule(context, art.progress, theme.accent, theme.muted),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply { topMargin = dp(Gap.XS) },
            )
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    cover.animate()
                        .scaleX(1.018f)
                        .scaleY(1.018f)
                        .rotationY(-5.5f)
                        .rotationX(1.6f)
                        .translationY(-dp(3).toFloat())
                        .setDuration(120)
                        .start()

                MotionEvent.ACTION_UP -> {
                    cover.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotationY(0f)
                        .rotationX(0f)
                        .translationY(0f)
                        .setDuration(220)
                        .start()
                    performClick()
                }

                MotionEvent.ACTION_CANCEL ->
                    cover.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotationY(0f)
                        .rotationX(0f)
                        .translationY(0f)
                        .setDuration(220)
                        .start()
            }
            true
        }
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL, serif: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color)
            typeface = if (serif) Type.serif(style) else Type.sans(style)
            includeFontPadding = false
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/**
 * A walnut shelf slowly being reclaimed by the enchanted forest. The foliage stays on the front
 * lip so it adds atmosphere without covering titles or stealing touch area from the books above.
 */
private class BookshelfPlankView(context: Context, private val rowIndex: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val density = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(0xFFC39A62.toInt(), 0xFF80552F.toInt(), 0xFF3C281B.toInt()),
            floatArrayOf(0f, .28f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(0f, 0f, w, h, 4f * density, 4f * density, paint)
        paint.shader = null
        paint.strokeWidth = density
        paint.color = 0x48F6DCA8
        canvas.drawLine(3f * density, 2f * density, w - 3f * density, 2f * density, paint)
        paint.color = 0x28533322
        repeat(4) { index ->
            val y = h * (.36f + index * .13f)
            val inset = (index % 2) * 18f * density
            canvas.drawLine(inset, y, w - 11f * density, y + density, paint)
        }
        paint.color = 0x50000000
        canvas.drawRect(4f * density, h - 2f * density, w - 4f * density, h, paint)

        drawVines(canvas, w, h)
    }

    private fun drawVines(canvas: Canvas, w: Float, h: Float) {
        val mirrored = rowIndex % 2 == 1
        val direction = if (mirrored) -1f else 1f
        val startX = if (mirrored) w + 5f * density else -5f * density
        val endX = if (mirrored) w * .38f else w * .62f

        // One loose stem crosses only part of the shelf, keeping the effect organic and restrained.
        path.reset()
        path.moveTo(startX, h * .55f)
        path.cubicTo(
            startX + direction * w * .16f,
            h * .18f,
            startX + direction * w * .34f,
            h * .92f,
            endX,
            h * .48f,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.2f * density
        paint.color = 0xE42E6A43.toInt()
        canvas.drawPath(path, paint)
        paint.strokeWidth = .75f * density
        paint.color = 0xB893CB8A.toInt()
        canvas.drawPath(path, paint)

        val leafFractions = floatArrayOf(.12f, .25f, .39f, .53f, .67f, .81f)
        leafFractions.forEachIndexed { index, fraction ->
            val x = startX + (endX - startX) * fraction
            val y = h * (.48f + kotlin.math.sin((fraction * 10f + rowIndex) * 1.15f) * .18f)
            val above = (index + rowIndex) % 2 == 0
            drawLeaf(canvas, x, y, if (above) -34f * direction else 34f * direction)
            if (index == 1 || index == 4) drawThorn(canvas, x, y, above, direction)
        }

        // A small curling tendril makes the vine feel alive without requiring constant animation.
        val curlX = if (mirrored) w * .73f else w * .27f
        val curlDirection = if (mirrored) -1f else 1f
        path.reset()
        path.moveTo(curlX, h * .43f)
        path.cubicTo(
            curlX + curlDirection * 12f * density,
            h * .05f,
            curlX + curlDirection * 20f * density,
            h * .82f,
            curlX + curlDirection * 4f * density,
            h * .73f,
        )
        paint.color = 0xB93A7950.toInt()
        paint.strokeWidth = 1.2f * density
        canvas.drawPath(path, paint)

        val firstRoseX = if (mirrored) w * .82f else w * .18f
        val secondRoseX = if (mirrored) w * .51f else w * .49f
        drawRose(canvas, firstRoseX, h * .48f, 6.2f * density)
        if (w > 520f * density) drawRose(canvas, secondRoseX, h * .62f, 4.6f * density)

        // Pinprick light catches on the thorns like dew, tying the shelf into the forest fireflies.
        paint.style = Paint.Style.FILL
        paint.color = 0xA9DDF3B5.toInt()
        canvas.drawCircle(firstRoseX - direction * 12f * density, h * .2f, 1.15f * density, paint)
        paint.color = 0x43DDF3B5
        canvas.drawCircle(firstRoseX - direction * 12f * density, h * .2f, 3.5f * density, paint)
    }

    private fun drawLeaf(canvas: Canvas, x: Float, y: Float, rotation: Float) {
        canvas.save()
        canvas.rotate(rotation, x, y)
        paint.style = Paint.Style.FILL
        paint.color = 0xE34B875A.toInt()
        canvas.drawOval(
            x - 1f * density,
            y - 4.3f * density,
            x + 7.5f * density,
            y + 4.3f * density,
            paint,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .65f * density
        paint.color = 0xA9AFD38B.toInt()
        canvas.drawLine(x, y, x + 6f * density, y, paint)
        canvas.restore()
    }

    private fun drawThorn(canvas: Canvas, x: Float, y: Float, above: Boolean, direction: Float) {
        val thornY = if (above) y + 1.5f * density else y - 1.5f * density
        val tipY = thornY + if (above) 5f * density else -5f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.15f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xD24B613D.toInt()
        canvas.drawLine(x, thornY, x - 3.4f * density * direction, tipY, paint)
    }

    private fun drawRose(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0x68220B17
        canvas.drawCircle(centerX + density, centerY + 1.5f * density, radius * 1.18f, paint)
        repeat(5) { petal ->
            val angle = petal * (Math.PI * 2.0 / 5.0) - Math.PI / 2.0
            val petalX = centerX + kotlin.math.cos(angle).toFloat() * radius * .48f
            val petalY = centerY + kotlin.math.sin(angle).toFloat() * radius * .48f
            paint.color = if ((petal + rowIndex) % 2 == 0) 0xFFE05B73.toInt() else 0xFFC83D5B.toInt()
            canvas.drawCircle(petalX, petalY, radius * .58f, paint)
        }
        paint.color = 0xFF762038.toInt()
        canvas.drawCircle(centerX, centerY, radius * .43f, paint)
        paint.color = 0xFFF2A3AD.toInt()
        canvas.drawCircle(centerX - radius * .13f, centerY - radius * .16f, radius * .13f, paint)
        paint.style = Paint.Style.FILL
    }
}
