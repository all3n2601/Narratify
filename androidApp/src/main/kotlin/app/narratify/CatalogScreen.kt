package app.narratify

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast

class CatalogScreen(
    context: Context,
    private val repository: LocalLibraryRepository,
    onNavigate: (AppSection) -> Unit,
    private val onImported: () -> Unit,
) : DestinationScreen(context, AppSection.DISCOVER, onNavigate) {
    private val service = BookCatalogService()
    private val coverLoader = CatalogCoverLoader()
    private val results = LinearLayout(context)
    private val status = label("Search across five open book catalogs.", Type.BODY, palette.muted)
    private val spinner = ProgressBar(context).apply {
        visibility = GONE
        indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.accent)
    }
    private var searchGeneration = 0
    private val search = EditText(context).apply {
        hint = "Title or author"
        setSingleLine(true)
        background = outlineShape(palette.outline, Radius.INPUT)
        setPadding(dp(Gap.SM), 0, dp(Gap.SM), 0)
        textSize = Type.BODY
        typeface = Type.sans()
        setTextColor(palette.ink)
        setHintTextColor(palette.muted)
        contentDescription = "Search open book catalogs"
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
    }

    init {
        results.orientation = LinearLayout.VERTICAL
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (tablet) Gap.XXL else Gap.LG), dp(if (tablet) Gap.XL else Gap.MD), dp(if (tablet) Gap.XXL else Gap.LG), dp(Gap.SECTION))
            addView(pageHeader("OPEN CATALOG", "Discover books", "One search across Project Gutenberg, Open Library, Google Books, Internet Archive, and LibriVox. Narratify only imports direct public EPUB files."))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(
                    primaryAction("Search") { runSearch() },
                    LinearLayout.LayoutParams(dp(92), dp(48)).apply { marginStart = dp(Gap.XS) },
                )
            }, topMargin(Gap.LG))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(spinner, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(Gap.SM) })
                addView(status)
            }, topMargin(Gap.MD))
            addView(results, topMargin(Gap.SM))
        }
        install(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(body) })
    }

    fun release() {
        searchGeneration++
        service.close()
        coverLoader.close()
    }

    private fun runSearch() {
        val query = search.text.toString().trim()
        if (query.length < 2) {
            status.text = "Enter at least two characters."
            return
        }
        spinner.visibility = VISIBLE
        status.text = "Searching catalogs…"
        results.removeAllViews()
        val generation = ++searchGeneration
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
        service.search(query) { books, failures ->
            post {
                if (generation != searchGeneration) return@post
                spinner.visibility = GONE
                status.text = when {
                    books.isEmpty() -> "No results. ${if (failures > 0) "$failures sources were unavailable." else "Try another search."}"
                    failures > 0 -> "${books.size} combined results · $failures source${if (failures == 1) "" else "s"} unavailable"
                    else -> "${books.size} combined results"
                }
                books.forEach { results.addView(resultCard(it), topMargin(Gap.SM)) }
            }
        }
    }

    private fun resultCard(book: CatalogBook) = card().apply {
        val cover = BookCoverView(context, coverArtFor(book)).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cameraDistance = dp(1_200).toFloat()
        }
        coverLoader.load(book.coverUrl) { bitmap ->
            if (cover.isAttachedToWindow) cover.setCoverBitmap(bitmap)
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.TOP
            addView(
                FrameLayout(context).apply {
                    addView(cover, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                },
                LinearLayout.LayoutParams(dp(if (tablet) 104 else 88), dp(if (tablet) 156 else 132)).apply {
                    marginEnd = dp(Gap.MD)
                },
            )
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(context.eyebrow(book.sources, palette.accent))
                addView(label(book.title, Type.TITLE, palette.ink, Typeface.BOLD, serif = true), topMargin(Gap.XS))
                addView(label(book.author, Type.BODY, palette.muted), topMargin(Gap.XXS))
                book.note?.let { addView(label(it, Type.LABEL, palette.muted), topMargin(Gap.XS)) }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(context.ruleView(palette.rule, topMarginDp = Gap.SM))
        // Import is the consequential action, so it is the only filled control in the row.
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(
                action("View source") { openSource(book.detailUrl) },
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)),
            )
            addView(android.widget.Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            book.epubUrl?.let { url ->
                addView(
                    primaryAction("Add EPUB") { import(book, url) },
                    LinearLayout.LayoutParams(dp(112), dp(44)).apply { marginStart = dp(Gap.XS) },
                )
            }
        }, topMargin(Gap.SM))
    }

    private fun action(title: String, perform: () -> Unit) =
        context.styledText(title, Type.LABEL, palette.accent, Typeface.BOLD, tracking = Type.TRACKING_LABEL).apply {
            gravity = Gravity.CENTER
            setPadding(dp(Gap.SM), 0, dp(Gap.SM), 0)
            background = outlineShape(palette.outline, Radius.INPUT)
            isClickable = true
            isFocusable = true
            setOnClickListener { perform() }
        }

    private fun openSource(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "No browser is available.", Toast.LENGTH_SHORT).show() }
    }

    private fun import(book: CatalogBook, url: String) {
        status.text = "Adding ${book.title}…"
        Thread {
            val result = repository.importRemote(url, "${book.title.safeFilename()}.epub")
            post {
                result.onSuccess {
                    Toast.makeText(context, "Added ${it.title}", Toast.LENGTH_SHORT).show()
                    onImported()
                }.onFailure { status.text = it.message ?: "The EPUB could not be added." }
            }
        }.start()
    }

    private fun String.safeFilename(): String = replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(80).ifBlank { "book" }
}
