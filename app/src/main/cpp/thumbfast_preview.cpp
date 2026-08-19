// SPDX-License-Identifier: AGPL-3.0-or-later

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

constexpr const char* TAG = "ThumbFastNative";
constexpr int MPV_FORMAT_FLAG = 3;
constexpr int MPV_FORMAT_INT64 = 4;
constexpr int MPV_FORMAT_DOUBLE = 5;
constexpr int MPV_RENDER_PARAM_INVALID = 0;
constexpr int MPV_RENDER_PARAM_API_TYPE = 1;
constexpr int MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12;
constexpr int MPV_RENDER_PARAM_SW_SIZE = 17;
constexpr int MPV_RENDER_PARAM_SW_FORMAT = 18;
constexpr int MPV_RENDER_PARAM_SW_STRIDE = 19;
constexpr int MPV_RENDER_PARAM_SW_POINTER = 20;
constexpr uint64_t MPV_RENDER_UPDATE_FRAME = 1ULL << 0;
constexpr int PREVIEW_MAX_EDGE = 240;
constexpr auto FAST_SEEK_PERIOD = std::chrono::milliseconds(50);
constexpr auto EXACT_SETTLE_DELAY = std::chrono::milliseconds(130);
constexpr auto SOURCE_READY_TIMEOUT = std::chrono::seconds(4);
constexpr auto SEEK_READY_TIMEOUT = std::chrono::milliseconds(450);
constexpr auto FRAME_READY_TIMEOUT = std::chrono::milliseconds(250);

struct mpv_handle;
struct mpv_render_context;

struct mpv_render_param {
  int type;
  void* data;
};

using mpv_create_fn = mpv_handle* (*)();
using mpv_initialize_fn = int (*)(mpv_handle*);
using mpv_terminate_destroy_fn = void (*)(mpv_handle*);
using mpv_destroy_fn = void (*)(mpv_handle*);
using mpv_set_option_string_fn = int (*)(mpv_handle*, const char*, const char*);
using mpv_set_property_string_fn = int (*)(mpv_handle*, const char*, const char*);
using mpv_get_property_fn = int (*)(mpv_handle*, const char*, int, void*);
using mpv_command_fn = int (*)(mpv_handle*, const char**);
using mpv_render_context_create_fn = int (*)(mpv_render_context**, mpv_handle*, mpv_render_param*);
using mpv_render_context_update_fn = uint64_t (*)(mpv_render_context*);
using mpv_render_context_render_fn = int (*)(mpv_render_context*, mpv_render_param*);
using mpv_render_context_free_fn = void (*)(mpv_render_context*);

class MpvApi {
 public:
  ~MpvApi() {
    if (library_ != nullptr) {
      dlclose(library_);
    }
  }

  bool load() {
    library_ = dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL);
    if (library_ == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen(libmpv.so) failed: %s", dlerror());
      return false;
    }

    return symbol(create, "mpv_create") &&
           symbol(initialize, "mpv_initialize") &&
           (symbol(terminate_destroy, "mpv_terminate_destroy", false) || symbol(destroy, "mpv_destroy")) &&
           symbol(set_option_string, "mpv_set_option_string") &&
           symbol(set_property_string, "mpv_set_property_string") &&
           symbol(get_property, "mpv_get_property") &&
           symbol(command, "mpv_command") &&
           symbol(render_context_create, "mpv_render_context_create") &&
           symbol(render_context_update, "mpv_render_context_update") &&
           symbol(render_context_render, "mpv_render_context_render") &&
           symbol(render_context_free, "mpv_render_context_free");
  }

  mpv_create_fn create = nullptr;
  mpv_initialize_fn initialize = nullptr;
  mpv_terminate_destroy_fn terminate_destroy = nullptr;
  mpv_destroy_fn destroy = nullptr;
  mpv_set_option_string_fn set_option_string = nullptr;
  mpv_set_property_string_fn set_property_string = nullptr;
  mpv_get_property_fn get_property = nullptr;
  mpv_command_fn command = nullptr;
  mpv_render_context_create_fn render_context_create = nullptr;
  mpv_render_context_update_fn render_context_update = nullptr;
  mpv_render_context_render_fn render_context_render = nullptr;
  mpv_render_context_free_fn render_context_free = nullptr;

 private:
  template <typename T>
  bool symbol(T& target, const char* name, bool required = true) {
    target = reinterpret_cast<T>(dlsym(library_, name));
    if (target == nullptr && required) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "Missing libmpv symbol %s", name);
      return false;
    }
    return target != nullptr;
  }

  void* library_ = nullptr;
};

struct Request {
  uint64_t generation = 0;
  int source_epoch = 0;
  std::string source;
  std::string user_agent;
  std::string http_headers;
  double position = 0.0;
};

struct RenderRequest {
  uint64_t generation = 0;
  int source_epoch = 0;
  int width = PREVIEW_MAX_EDGE;
  int height = PREVIEW_MAX_EDGE;
};

class ThumbFastEngine {
 public:
  ThumbFastEngine() {
    if (!api_.load()) return;

    mpv_ = api_.create();
    if (mpv_ == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "mpv_create failed");
      return;
    }

    // Mirror thumbfast.lua's architecture: this is a dedicated, persistent, paused decoder.
    // It owns no audio, subtitles, visible Android Surface, hardware decoder, or main-player state.
    setOption("config", "no");
    setOption("vo", "libmpv");
    setOption("pause", "yes");
    setOption("keep-open", "yes");
    setOption("audio", "no");
    setOption("sub", "no");
    setOption("aid", "no");
    setOption("sid", "no");
    setOption("osd-level", "0");
    setOption("hwdec", "no");
    setOption("demuxer-readahead-secs", "0");
    setOption("demuxer-max-bytes", "128KiB");
    setOption("vd-lavc-skiploopfilter", "all");
    setOption("vd-lavc-fast", "yes");
    setOption("vd-lavc-threads", "2");
    setOption("sws-scaler", "fast-bilinear");
    setOption("sw-fast", "yes");
    setOption("video-timing-offset", "0");

    if (api_.initialize(mpv_) < 0) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "mpv_initialize failed");
      destroyCore();
      return;
    }

    // The render API must exist before loadfile can create the VO.
    render_thread_ = std::thread([this] { renderLoop(); });
    {
      std::unique_lock<std::mutex> lock(render_mutex_);
      render_ready_cv_.wait_for(lock, std::chrono::seconds(2), [this] {
        return render_ready_ || render_failed_ || stop_.load();
      });
    }

    if (!render_ready_) {
      __android_log_print(ANDROID_LOG_ERROR, TAG, "software render context creation failed");
      stop_.store(true);
      render_cv_.notify_all();
      if (render_thread_.joinable()) render_thread_.join();
      destroyCore();
      return;
    }

    valid_.store(true);
    worker_thread_ = std::thread([this] { workerLoop(); });
  }

  ~ThumbFastEngine() {
    shutdown();
  }

  bool valid() const { return valid_.load(); }

  void request(
      std::string source,
      std::string user_agent,
      std::string http_headers,
      double position,
      int source_epoch) {
    if (!valid()) return;

    {
      std::lock_guard<std::mutex> lock(request_mutex_);
      latest_request_.generation = ++generation_;
      latest_request_.source_epoch = source_epoch;
      latest_request_.source = std::move(source);
      latest_request_.user_agent = std::move(user_agent);
      latest_request_.http_headers = std::move(http_headers);
      latest_request_.position = position < 0.0 ? 0.0 : position;
      active_ = true;
      active_requested_.store(true);
      current_generation_.store(latest_request_.generation);
      current_source_epoch_.store(source_epoch);
    }
    request_cv_.notify_all();
  }

  void clear() {
    {
      std::lock_guard<std::mutex> lock(request_mutex_);
      active_ = false;
      active_requested_.store(false);
      ++generation_;
      current_generation_.store(generation_);
    }
    request_cv_.notify_all();
    render_cv_.notify_all();
  }

  jintArray waitForFrame(JNIEnv* env, int after_serial, int timeout_ms) {
    {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      ++external_waiters_;
    }

    auto release_waiter = [this] {
      std::lock_guard<std::mutex> lock(waiter_mutex_);
      --external_waiters_;
      waiter_cv_.notify_all();
    };

    std::unique_lock<std::mutex> lock(frame_mutex_);
    const bool ready = frame_cv_.wait_for(
        lock,
        std::chrono::milliseconds(timeout_ms < 0 ? 0 : timeout_ms),
        [this, after_serial] { return stop_.load() || frame_serial_ > after_serial; });

    if (!ready || stop_.load() || frame_serial_ <= after_serial || frame_pixels_.empty()) {
      lock.unlock();
      release_waiter();
      return nullptr;
    }

    const int serial = frame_serial_;
    const int source_epoch = frame_source_epoch_;
    const int width = frame_width_;
    const int height = frame_height_;
    const std::vector<jint> pixels = frame_pixels_;
    lock.unlock();

    const jsize output_size = static_cast<jsize>(4 + pixels.size());
    jintArray result = env->NewIntArray(output_size);
    if (result != nullptr) {
      jint header[4] = {serial, source_epoch, width, height};
      env->SetIntArrayRegion(result, 0, 4, header);
      if (!pixels.empty()) {
        env->SetIntArrayRegion(result, 4, static_cast<jsize>(pixels.size()), pixels.data());
      }
    }

    release_waiter();
    return result;
  }

  void shutdown() {
    bool expected = false;
    if (!shutdown_started_.compare_exchange_strong(expected, true)) return;

    stop_.store(true);
    valid_.store(false);
    active_requested_.store(false);
    request_cv_.notify_all();
    render_cv_.notify_all();
    frame_cv_.notify_all();

    if (worker_thread_.joinable()) worker_thread_.join();
    if (render_thread_.joinable()) render_thread_.join();

    // A Kotlin frame poll can still be unwinding from nativeWaitForFrame(). Keep the object alive
    // until it leaves the method so no JNI caller can observe a freed engine.
    {
      std::unique_lock<std::mutex> lock(waiter_mutex_);
      waiter_cv_.wait_for(lock, std::chrono::seconds(1), [this] { return external_waiters_ == 0; });
    }

    destroyCore();
  }

 private:
  void setOption(const char* name, const char* value) {
    if (mpv_ != nullptr) api_.set_option_string(mpv_, name, value);
  }

  void destroyCore() {
    if (mpv_ == nullptr) return;
    if (api_.terminate_destroy != nullptr) {
      api_.terminate_destroy(mpv_);
    } else if (api_.destroy != nullptr) {
      api_.destroy(mpv_);
    }
    mpv_ = nullptr;
  }

  bool requestIsCurrent(const Request& request) const {
    return !stop_.load() && active_requested_.load() && current_generation_.load() == request.generation;
  }

  bool sourceIsCurrent(const Request& request) const {
    return !stop_.load() && active_requested_.load() && current_source_epoch_.load() == request.source_epoch;
  }

  bool loadSource(const Request& request) {
    if (request.source.empty() || mpv_ == nullptr) return false;

    // A newer scrub position must not abort media initialization. Only clear/teardown or a genuine
    // source change invalidates an in-flight load, so rapid pointer events cannot leave a half-open
    // decoder session behind.
    if (loaded_source_epoch_ == request.source_epoch && loaded_source_ == request.source && source_ready_) {
      return true;
    }

    if (loaded_source_epoch_ != request.source_epoch || loaded_source_ != request.source) {
      api_.set_property_string(mpv_, "user-agent", request.user_agent.c_str());
      api_.set_property_string(mpv_, "http-header-fields", request.http_headers.c_str());

      const char* args[] = {"loadfile", request.source.c_str(), "replace", nullptr};
      if (api_.command(mpv_, args) < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "loadfile failed for preview source");
        source_ready_ = false;
        return false;
      }

      loaded_source_epoch_ = request.source_epoch;
      loaded_source_ = request.source;
      source_ready_ = false;
    }

    const auto deadline = std::chrono::steady_clock::now() + SOURCE_READY_TIMEOUT;
    while (sourceIsCurrent(request) && std::chrono::steady_clock::now() < deadline) {
      int64_t width = 0;
      int64_t height = 0;
      const bool has_video =
          api_.get_property(mpv_, "video-params/w", MPV_FORMAT_INT64, &width) >= 0 && width > 0 &&
          api_.get_property(mpv_, "video-params/h", MPV_FORMAT_INT64, &height) >= 0 && height > 0;
      if (has_video) {
        source_ready_ = true;
        return true;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    if (!sourceIsCurrent(request)) {
      source_ready_ = false;
    }
    return false;
  }

  bool waitForSeek(const Request& request) {
    const auto deadline = std::chrono::steady_clock::now() + SEEK_READY_TIMEOUT;
    bool saw_seeking = false;
    while (requestIsCurrent(request) && std::chrono::steady_clock::now() < deadline) {
      int seeking = 0;
      if (api_.get_property(mpv_, "seeking", MPV_FORMAT_FLAG, &seeking) >= 0) {
        if (seeking != 0) {
          saw_seeking = true;
        } else if (saw_seeking) {
          return true;
        } else {
          // A nearby keyframe seek can finish before the first poll observes `seeking`.
          std::this_thread::sleep_for(std::chrono::milliseconds(6));
          int seeking_again = 0;
          if (api_.get_property(mpv_, "seeking", MPV_FORMAT_FLAG, &seeking_again) >= 0 && seeking_again == 0) {
            return true;
          }
        }
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(3));
    }
    return requestIsCurrent(request);
  }

  bool seek(const Request& request, bool exact) {
    if (mpv_ == nullptr || !requestIsCurrent(request)) return false;
    const std::string position = std::to_string(request.position);
    const char* mode = exact ? "absolute+exact" : "absolute+keyframes";
    const char* args[] = {"seek", position.c_str(), mode, nullptr};
    return api_.command(mpv_, args) >= 0 && waitForSeek(request);
  }

  void scheduleRender(const Request& request) {
    // Normal libmpv property access belongs on the worker thread. The render thread must
    // stay inside mpv_render_* calls while it owns the render context.
    const auto [width, height] = previewSize();
    {
      std::lock_guard<std::mutex> lock(render_mutex_);
      pending_render_.generation = request.generation;
      pending_render_.source_epoch = request.source_epoch;
      pending_render_.width = width;
      pending_render_.height = height;
      has_pending_render_ = true;
    }
    render_cv_.notify_all();
  }

  bool waitForFirstRender(const Request& request) {
    const auto deadline = std::chrono::steady_clock::now() + FRAME_READY_TIMEOUT;
    std::unique_lock<std::mutex> lock(request_mutex_);
    while (requestIsCurrent(request) && rendered_generation_.load() < request.generation &&
           std::chrono::steady_clock::now() < deadline) {
      request_cv_.wait_for(lock, std::chrono::milliseconds(5));
    }
    return rendered_generation_.load() >= request.generation;
  }

  bool waitForSettleOrNewRequest(const Request& request) {
    std::unique_lock<std::mutex> lock(request_mutex_);
    return !request_cv_.wait_for(lock, EXACT_SETTLE_DELAY, [this, &request] {
      return stop_.load() || !active_ || generation_ != request.generation;
    });
  }

  void workerLoop() {
    auto last_fast_seek = std::chrono::steady_clock::time_point::min();

    while (!stop_.load()) {
      Request request;
      {
        std::unique_lock<std::mutex> lock(request_mutex_);
        request_cv_.wait(lock, [this] {
          return stop_.load() || (active_ && latest_request_.generation > processed_generation_);
        });
        if (stop_.load()) break;
        request = latest_request_;
        processed_generation_ = request.generation;
      }

      if (!loadSource(request)) continue;
      if (!requestIsCurrent(request)) continue;

      const auto now = std::chrono::steady_clock::now();
      const auto due = last_fast_seek + FAST_SEEK_PERIOD;
      if (last_fast_seek != std::chrono::steady_clock::time_point::min() && now < due) {
        std::unique_lock<std::mutex> lock(request_mutex_);
        request_cv_.wait_until(lock, due, [this, &request] {
          return stop_.load() || !active_ || generation_ != request.generation;
        });
        if (!requestIsCurrent(request)) continue;
      }

      // While the finger moves, use cheap keyframe seeks and let newest-target-wins conflation
      // discard intermediate positions that the decoder could never display in time anyway.
      if (!seek(request, false) || !requestIsCurrent(request)) continue;
      last_fast_seek = std::chrono::steady_clock::now();
      scheduleRender(request);
      waitForFirstRender(request);
      if (!requestIsCurrent(request)) continue;

      // Once input settles, refine only the secondary core to the exact requested timestamp.
      if (!waitForSettleOrNewRequest(request) || !requestIsCurrent(request)) continue;
      if (!seek(request, true) || !requestIsCurrent(request)) continue;
      scheduleRender(request);
    }
  }

  std::pair<int, int> previewSize() {
    double aspect = 0.0;
    if (api_.get_property(mpv_, "video-params/aspect", MPV_FORMAT_DOUBLE, &aspect) < 0 ||
        !std::isfinite(aspect) || aspect <= 0.0) {
      int64_t width = 0;
      int64_t height = 0;
      if (api_.get_property(mpv_, "video-params/w", MPV_FORMAT_INT64, &width) >= 0 && width > 0 &&
          api_.get_property(mpv_, "video-params/h", MPV_FORMAT_INT64, &height) >= 0 && height > 0) {
        aspect = static_cast<double>(width) / static_cast<double>(height);
      }
    }
    if (!std::isfinite(aspect) || aspect <= 0.0) aspect = 16.0 / 9.0;

    int width = PREVIEW_MAX_EDGE;
    int height = PREVIEW_MAX_EDGE;
    if (aspect >= 1.0) {
      height = static_cast<int>(std::lround(PREVIEW_MAX_EDGE / aspect));
    } else {
      width = static_cast<int>(std::lround(PREVIEW_MAX_EDGE * aspect));
    }
    width = std::max(2, std::min(PREVIEW_MAX_EDGE, width));
    height = std::max(2, std::min(PREVIEW_MAX_EDGE, height));
    return {width, height};
  }

  void renderLoop() {
    char api_type[] = "sw";
    mpv_render_param create_params[] = {
        {MPV_RENDER_PARAM_API_TYPE, api_type},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    mpv_render_context* render_context = nullptr;
    const int create_result = api_.render_context_create(&render_context, mpv_, create_params);

    // One maximum-size aligned surface is reused for every frame. Allocate it before publishing
    // render_ready_ so construction cannot succeed with a dead render thread.
    const size_t stride = static_cast<size_t>((PREVIEW_MAX_EDGE * 4 + 63) & ~63);
    void* aligned_memory = nullptr;
    const bool buffer_ready =
        create_result >= 0 && render_context != nullptr &&
        posix_memalign(&aligned_memory, 64, stride * PREVIEW_MAX_EDGE) == 0 && aligned_memory != nullptr;

    {
      std::lock_guard<std::mutex> lock(render_mutex_);
      render_context_ = render_context;
      render_ready_ = buffer_ready;
      render_failed_ = !buffer_ready;
    }
    render_ready_cv_.notify_all();

    if (!buffer_ready) {
      if (aligned_memory != nullptr) free(aligned_memory);
      if (render_context != nullptr) api_.render_context_free(render_context);
      render_context_ = nullptr;
      return;
    }

    auto* bytes = static_cast<uint8_t*>(aligned_memory);

    while (!stop_.load()) {
      RenderRequest request;
      {
        std::unique_lock<std::mutex> lock(render_mutex_);
        render_cv_.wait(lock, [this] { return stop_.load() || has_pending_render_; });
        if (stop_.load()) break;
        request = pending_render_;
        has_pending_render_ = false;
      }

      if (!active_requested_.load() || current_generation_.load() != request.generation ||
          current_source_epoch_.load() != request.source_epoch) {
        continue;
      }

      bool frame_ready = false;
      const auto deadline = std::chrono::steady_clock::now() + FRAME_READY_TIMEOUT;
      while (!stop_.load() && active_requested_.load() && current_generation_.load() == request.generation &&
             current_source_epoch_.load() == request.source_epoch &&
             std::chrono::steady_clock::now() < deadline) {
        if ((api_.render_context_update(render_context) & MPV_RENDER_UPDATE_FRAME) != 0) {
          frame_ready = true;
          break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
      }
      if (!frame_ready || !active_requested_.load() || current_generation_.load() != request.generation ||
          current_source_epoch_.load() != request.source_epoch) {
        continue;
      }

      const int width = request.width;
      const int height = request.height;
      std::memset(bytes, 0, stride * static_cast<size_t>(height));
      int size[2] = {width, height};
      char format[] = "bgr0";
      size_t render_stride = stride;
      int block_for_target_time = 0;
      mpv_render_param render_params[] = {
          {MPV_RENDER_PARAM_SW_SIZE, size},
          {MPV_RENDER_PARAM_SW_FORMAT, format},
          {MPV_RENDER_PARAM_SW_STRIDE, &render_stride},
          {MPV_RENDER_PARAM_SW_POINTER, bytes},
          {MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME, &block_for_target_time},
          {MPV_RENDER_PARAM_INVALID, nullptr},
      };

      if (api_.render_context_render(render_context, render_params) < 0 ||
          !active_requested_.load() || current_generation_.load() != request.generation ||
          current_source_epoch_.load() != request.source_epoch) {
        continue;
      }

      std::vector<jint> pixels(static_cast<size_t>(width * height));
for (int y = 0; y < height; ++y) {
  const uint8_t* row = bytes + static_cast<size_t>(y) * stride;
  for (int x = 0; x < width; ++x) {
    const uint8_t b = row[x * 4 + 0];
    const uint8_t g = row[x * 4 + 1];
    const uint8_t r = row[x * 4 + 2];
    pixels[static_cast<size_t>(y * width + x)] =
        static_cast<jint>(0xFF000000u | (static_cast<uint32_t>(r) << 16) |
                          (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b));
  }
}

// Serialize final publication with request generation changes. A newer pointer target can
// arrive while BGR0 is being converted; an older frame must never become visible after it.
{
  std::lock_guard<std::mutex> request_lock(request_mutex_);
  if (stop_.load() || !active_ || generation_ != request.generation ||
      current_source_epoch_.load() != request.source_epoch) {
    continue;
  }
  std::lock_guard<std::mutex> frame_lock(frame_mutex_);
  frame_pixels_ = std::move(pixels);
  frame_width_ = width;
  frame_height_ = height;
  frame_source_epoch_ = request.source_epoch;
  ++frame_serial_;
  rendered_generation_.store(request.generation);
}
frame_cv_.notify_all();
request_cv_.notify_all();
    }

    free(aligned_memory);
    api_.render_context_free(render_context);
    render_context_ = nullptr;
  }

  MpvApi api_;
  mpv_handle* mpv_ = nullptr;
  mpv_render_context* render_context_ = nullptr;
  std::atomic<bool> valid_{false};
  std::atomic<bool> stop_{false};
  std::atomic<bool> shutdown_started_{false};
  std::atomic<bool> active_requested_{false};
  std::atomic<uint64_t> current_generation_{0};
  std::atomic<uint64_t> rendered_generation_{0};
  std::atomic<int> current_source_epoch_{0};

  std::thread worker_thread_;
  std::thread render_thread_;

  std::mutex request_mutex_;
  std::condition_variable request_cv_;
  Request latest_request_;
  uint64_t generation_ = 0;
  uint64_t processed_generation_ = 0;
  bool active_ = false;
  int loaded_source_epoch_ = -1;
  bool source_ready_ = false;
  std::string loaded_source_;

  std::mutex render_mutex_;
  std::condition_variable render_cv_;
  std::condition_variable render_ready_cv_;
  RenderRequest pending_render_;
  bool has_pending_render_ = false;
  bool render_ready_ = false;
  bool render_failed_ = false;

  std::mutex frame_mutex_;
  std::condition_variable frame_cv_;
  std::vector<jint> frame_pixels_;
  int frame_serial_ = 0;
  int frame_source_epoch_ = 0;
  int frame_width_ = 0;
  int frame_height_ = 0;

  std::mutex waiter_mutex_;
  std::condition_variable waiter_cv_;
  int external_waiters_ = 0;
};

ThumbFastEngine* fromHandle(jlong handle) {
  return reinterpret_cast<ThumbFastEngine*>(static_cast<intptr_t>(handle));
}

std::string fromJString(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_gyrolet_mpvrx_ui_player_thumbfast_NativeThumbFast_nativeCreate0(JNIEnv*, jobject) {
  auto engine = std::make_unique<ThumbFastEngine>();
  if (!engine->valid()) return 0;
  return static_cast<jlong>(reinterpret_cast<intptr_t>(engine.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_thumbfast_NativeThumbFast_nativeRequest0(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring source,
    jstring user_agent,
    jstring http_headers,
    jdouble position,
    jint source_epoch) {
  ThumbFastEngine* engine = fromHandle(handle);
  if (engine == nullptr) return;
  engine->request(
      fromJString(env, source),
      fromJString(env, user_agent),
      fromJString(env, http_headers),
      static_cast<double>(position),
      static_cast<int>(source_epoch));
}

extern "C" JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_thumbfast_NativeThumbFast_nativeClear0(
    JNIEnv*, jobject, jlong handle) {
  ThumbFastEngine* engine = fromHandle(handle);
  if (engine != nullptr) engine->clear();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_app_gyrolet_mpvrx_ui_player_thumbfast_NativeThumbFast_nativeWaitForFrame0(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint after_serial,
    jint timeout_ms) {
  ThumbFastEngine* engine = fromHandle(handle);
  if (engine == nullptr) return nullptr;
  return engine->waitForFrame(env, after_serial, timeout_ms);
}

extern "C" JNIEXPORT void JNICALL
Java_app_gyrolet_mpvrx_ui_player_thumbfast_NativeThumbFast_nativeDestroy0(
    JNIEnv*, jobject, jlong handle) {
  std::unique_ptr<ThumbFastEngine> engine(fromHandle(handle));
  if (engine != nullptr) engine->shutdown();
}
