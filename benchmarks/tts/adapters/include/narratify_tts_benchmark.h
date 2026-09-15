#ifndef NARRATIFY_TTS_BENCHMARK_H
#define NARRATIFY_TTS_BENCHMARK_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct narratify_tts_session narratify_tts_session;
typedef void (*narratify_pcm_callback)(const int16_t *samples, size_t sample_count,
                                       uint32_t sample_rate_hz, void *context);

typedef struct {
  uint32_t text_start_codepoint;
  uint32_t text_end_codepoint;
  uint64_t start_sample;
  uint64_t end_sample;
} narratify_word_timing;

typedef struct {
  uint64_t sample_count;
  double first_audio_ms;
  double synthesis_ms;
  double peak_rss_mb;
  const narratify_word_timing *word_timings;
  size_t word_timing_count;
} narratify_tts_result;

/* Implementations must not initiate network access or retain caller buffers. */
int narratify_tts_create(const char *local_manifest_json,
                         narratify_tts_session **session);
int narratify_tts_synthesize(narratify_tts_session *session,
                             const char *utf8_text,
                             const char *bcp47_language,
                             narratify_pcm_callback callback,
                             void *callback_context,
                             narratify_tts_result *result);
void narratify_tts_cancel(narratify_tts_session *session);
void narratify_tts_release_result(narratify_tts_session *session,
                                  narratify_tts_result *result);
void narratify_tts_destroy(narratify_tts_session *session);
const char *narratify_tts_last_error(narratify_tts_session *session);

#ifdef __cplusplus
}
#endif
#endif
