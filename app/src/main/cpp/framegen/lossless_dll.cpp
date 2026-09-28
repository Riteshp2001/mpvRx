// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <optional>
#include <span>
#include <string>
#include <mutex>
#include <vector>

#include <vulkan/vulkan.h>

#include "cityhash.h"
#include "lossless_dll.h"
#include "lsfg_translate.h"

namespace VideoCore::FrameGen {

namespace {

constexpr u16 DOS_MAGIC = 0x5A4D;
constexpr u32 PE_SIGNATURE = 0x00004550;
constexpr u16 PE32_MAGIC = 0x010B;
constexpr u16 PE32_PLUS_MAGIC = 0x020B;

constexpr size_t DOS_LFANEW_OFFSET = 0x3C;
constexpr size_t COFF_HEADER_SIZE = 20;
constexpr size_t OPTIONAL_HEADER_SIZE_OFFSET = 16;
constexpr size_t SECTION_HEADER_SIZE = 40;
constexpr size_t DATA_DIRECTORY_ENTRY_SIZE = 8;
constexpr size_t DATA_DIRECTORY_OFFSET_PE32 = 96;
constexpr size_t DATA_DIRECTORY_OFFSET_PE32_PLUS = 112;
constexpr size_t RESOURCE_DATA_DIRECTORY_INDEX = 2;

constexpr size_t RESOURCE_DIRECTORY_SIZE = 16;
constexpr size_t RESOURCE_NAMED_COUNT_OFFSET = 12;
constexpr size_t RESOURCE_ID_COUNT_OFFSET = 14;
constexpr size_t RESOURCE_ENTRY_SIZE = 8;
constexpr u32 RESOURCE_SUBDIRECTORY_FLAG = 0x80000000;
constexpr u32 RESOURCE_TYPE_RCDATA = 10;

constexpr u32 MIPMAPS_SHADER_ID = 255;
constexpr u32 GENERATE_SHADER_ID = 256;
constexpr u32 PERFORMANCE_SHADER_ID_FIRST = 280;
constexpr u32 PERFORMANCE_SHADER_ID_LAST = 302;

constexpr u32 CACHE_MAGIC = 0x4746534C; // "LSFG"
constexpr u32 CACHE_VERSION = 3;

#define LOSSLESS_DLL_FILE "Lossless.dll"
#define LOSSLESS_CACHE_FILE "lsfg_spirv.cache"

#pragma pack(push, 1)
struct CacheHeader {
    u32 magic;
    u32 version;
    u64 source_size;
    u64 source_hash;
    u32 module_count;
};
#pragma pack(pop)

struct Section {
    u32 virtual_address;
    u32 virtual_size;
    u32 raw_address;
    u32 raw_size;
};

struct ResourceEntry {
    u32 id;
    u32 offset;
    bool is_directory;
    bool is_named;
};

class ImageReader {
public:
    explicit ImageReader(std::span<const u8> image_) : image{image_} {}

    template <typename T>
    [[nodiscard]] bool Read(size_t offset, T& out_value) const {
        if (offset > image.size() || image.size() - offset < sizeof(T)) {
            return false;
        }
        std::memcpy(&out_value, image.data() + offset, sizeof(T));
        return true;
    }

    [[nodiscard]] bool Slice(size_t offset, size_t size, std::span<const u8>& out_slice) const {
        if (offset > image.size() || image.size() - offset < size) {
            return false;
        }
        out_slice = image.subspan(offset, size);
        return true;
    }

private:
    std::span<const u8> image;
};

[[nodiscard]] std::optional<size_t> FindPeHeader(const ImageReader& reader) {
    u16 dos_magic{};
    if (!reader.Read(0, dos_magic) || dos_magic != DOS_MAGIC) {
        return std::nullopt;
    }

    u32 pe_offset{};
    if (!reader.Read(DOS_LFANEW_OFFSET, pe_offset)) {
        return std::nullopt;
    }

    u32 pe_signature{};
    if (!reader.Read(pe_offset, pe_signature) || pe_signature != PE_SIGNATURE) {
        return std::nullopt;
    }

    return static_cast<size_t>(pe_offset);
}

[[nodiscard]] std::optional<size_t> FindDataDirectory(const ImageReader& reader,
                                                      size_t optional_header_offset) {
    u16 optional_magic{};
    if (!reader.Read(optional_header_offset, optional_magic)) {
        return std::nullopt;
    }

    switch (optional_magic) {
    case PE32_MAGIC:
        return optional_header_offset + DATA_DIRECTORY_OFFSET_PE32;
    case PE32_PLUS_MAGIC:
        return optional_header_offset + DATA_DIRECTORY_OFFSET_PE32_PLUS;
    default:
        return std::nullopt;
    }
}

[[nodiscard]] bool ReadSections(const ImageReader& reader, size_t pe_offset,
                                std::vector<Section>& out_sections) {
    u16 section_count{};
    u16 optional_header_size{};
    if (!reader.Read(pe_offset + 4 + 2, section_count) ||
        !reader.Read(pe_offset + 4 + OPTIONAL_HEADER_SIZE_OFFSET, optional_header_size)) {
        return false;
    }

    const size_t table_offset = pe_offset + 4 + COFF_HEADER_SIZE + optional_header_size;
    out_sections.reserve(section_count);
    for (size_t i = 0; i < section_count; ++i) {
        const size_t offset = table_offset + i * SECTION_HEADER_SIZE;
        Section section{};
        if (!reader.Read(offset + 8, section.virtual_size) ||
            !reader.Read(offset + 12, section.virtual_address) ||
            !reader.Read(offset + 16, section.raw_size) ||
            !reader.Read(offset + 20, section.raw_address)) {
            return false;
        }
        out_sections.push_back(section);
    }
    return true;
}

[[nodiscard]] std::optional<size_t> RvaToFileOffset(std::span<const Section> sections, u32 rva) {
    for (const Section& section : sections) {
        const u32 span = std::max(section.virtual_size, section.raw_size);
        if (span == 0 || rva < section.virtual_address) {
            continue;
        }
        const u32 relative = rva - section.virtual_address;
        if (relative < span) {
            return static_cast<size_t>(section.raw_address) + relative;
        }
    }
    return std::nullopt;
}

[[nodiscard]] bool ReadResourceEntries(const ImageReader& reader, size_t directory_offset,
                                       std::vector<ResourceEntry>& out_entries) {
    u16 named_count{};
    u16 id_count{};
    if (!reader.Read(directory_offset + RESOURCE_NAMED_COUNT_OFFSET, named_count) ||
        !reader.Read(directory_offset + RESOURCE_ID_COUNT_OFFSET, id_count)) {
        return false;
    }

    const size_t total = size_t{named_count} + size_t{id_count};
    out_entries.clear();
    out_entries.reserve(total);
    for (size_t i = 0; i < total; ++i) {
        const size_t offset = directory_offset + RESOURCE_DIRECTORY_SIZE + i * RESOURCE_ENTRY_SIZE;
        u32 name{};
        u32 data{};
        if (!reader.Read(offset, name) || !reader.Read(offset + 4, data)) {
            return false;
        }
        out_entries.push_back(ResourceEntry{
            .id = name & ~RESOURCE_SUBDIRECTORY_FLAG,
            .offset = data & ~RESOURCE_SUBDIRECTORY_FLAG,
            .is_directory = (data & RESOURCE_SUBDIRECTORY_FLAG) != 0,
            .is_named = (name & RESOURCE_SUBDIRECTORY_FLAG) != 0,
        });
    }
    return true;
}

[[nodiscard]] bool ReadResourceLeaf(const ImageReader& reader, std::span<const Section> sections,
                                    size_t leaf_offset, std::span<const u8>& out_data) {
    u32 data_rva{};
    u32 data_size{};
    if (!reader.Read(leaf_offset, data_rva) || !reader.Read(leaf_offset + 4, data_size) ||
        data_size == 0) {
        return false;
    }

    const std::optional<size_t> data_offset = RvaToFileOffset(sections, data_rva);
    if (!data_offset) {
        return false;
    }
    return reader.Slice(*data_offset, data_size, out_data);
}

using ResourceSpans = std::map<u32, std::span<const u8>>;

[[nodiscard]] bool CollectRcData(const ImageReader& reader, std::span<const Section> sections,
                                 size_t resource_base, ResourceSpans& out_resources) {
    std::vector<ResourceEntry> type_entries;
    if (!ReadResourceEntries(reader, resource_base, type_entries)) {
        return false;
    }

    for (const ResourceEntry& type_entry : type_entries) {
        if (type_entry.is_named || type_entry.id != RESOURCE_TYPE_RCDATA ||
            !type_entry.is_directory) {
            continue;
        }

        std::vector<ResourceEntry> name_entries;
        if (!ReadResourceEntries(reader, resource_base + type_entry.offset, name_entries)) {
            return false;
        }

        for (const ResourceEntry& name_entry : name_entries) {
            if (name_entry.is_named || !name_entry.is_directory) {
                continue;
            }

            std::vector<ResourceEntry> language_entries;
            if (!ReadResourceEntries(reader, resource_base + name_entry.offset, language_entries)) {
                return false;
            }

            for (const ResourceEntry& language_entry : language_entries) {
                if (language_entry.is_directory) {
                    continue;
                }
                std::span<const u8> data;
                if (!ReadResourceLeaf(reader, sections, resource_base + language_entry.offset,
                                      data)) {
                    continue;
                }
                out_resources.insert_or_assign(name_entry.id, data);
                break;
            }
        }
    }

    return true;
}

[[nodiscard]] std::vector<u32> PerformanceShaderIds() {
    std::vector<u32> ids{MIPMAPS_SHADER_ID, GENERATE_SHADER_ID};
    for (u32 id = PERFORMANCE_SHADER_ID_FIRST; id <= PERFORMANCE_SHADER_ID_LAST; ++id) {
        ids.push_back(id);
    }
    return ids;
}

template <typename Map>
[[nodiscard]] bool HasPerformanceShaders(const Map& resources) {
    const std::vector<u32> ids = PerformanceShaderIds();
    return std::ranges::all_of(ids, [&](u32 id) { return resources.contains(id); });
}

template <typename Map>
[[nodiscard]] bool HasNativeShaders(const Map& resources) {
    return std::ranges::all_of(PerformanceShaderIds(), [&](u32 id) {
        const auto hit = resources.find(id + PerformanceShader::NATIVE_FP16_OFFSET);
        return hit != resources.end() && IsSpirvModule(hit->second);
    });
}

[[nodiscard]] LosslessStatus TranslateAll(const ResourceSpans& resources,
                                          ShaderModules& out_modules) {
    out_modules.clear();
    for (const u32 id : PerformanceShaderIds()) {
        const auto hit = resources.find(id + PerformanceShader::NATIVE_FP16_OFFSET);
        if (hit == resources.end()) {
            return LosslessStatus::MissingShaders;
        }
        std::vector<u32> adopted = AdoptSpirvModule(hit->second);
        if (adopted.empty()) {
            return LosslessStatus::TranslationFailed;
        }
        out_modules.emplace(id, std::move(adopted));
    }
    return LosslessStatus::Ok;
}

[[nodiscard]] bool WriteShaderCache(const std::filesystem::path& path, const CacheHeader& header,
                                    const ShaderModules& modules) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);

    FILE* file = std::fopen(path.string().c_str(), "wb");
    if (!file) {
        return false;
    }

    if (std::fwrite(&header, sizeof(header), 1, file) != 1) {
        std::fclose(file);
        return false;
    }

    for (const auto& [id, words] : modules) {
        const u32 word_count = static_cast<u32>(words.size());
        if (std::fwrite(&id, sizeof(id), 1, file) != 1 ||
            std::fwrite(&word_count, sizeof(word_count), 1, file) != 1 ||
            std::fwrite(words.data(), sizeof(u32), words.size(), file) != words.size()) {
            std::fclose(file);
            return false;
        }
    }

    std::fclose(file);
    return true;
}

[[nodiscard]] bool ReadShaderCache(const std::filesystem::path& path, u64 source_size,
                                   u64 source_hash, ShaderModules& out_modules) {
    if (!std::filesystem::exists(path)) {
        return false;
    }

    FILE* file = std::fopen(path.string().c_str(), "rb");
    if (!file) {
        return false;
    }

    CacheHeader header{};
    if (std::fread(&header, sizeof(header), 1, file) != 1) {
        std::fclose(file);
        return false;
    }

    if (header.magic != CACHE_MAGIC || header.version != CACHE_VERSION ||
        header.source_size != source_size || header.source_hash != source_hash) {
        std::fclose(file);
        return false;
    }

    out_modules.clear();
    for (u32 i = 0; i < header.module_count; ++i) {
        u32 id{};
        u32 word_count{};
        if (std::fread(&id, sizeof(id), 1, file) != 1 ||
            std::fread(&word_count, sizeof(word_count), 1, file) != 1 || word_count == 0) {
            std::fclose(file);
            return false;
        }

        std::vector<u32> words(word_count);
        if (std::fread(words.data(), sizeof(u32), word_count, file) != word_count) {
            std::fclose(file);
            return false;
        }
        out_modules.emplace(id, std::move(words));
    }

    std::fclose(file);
    return HasPerformanceShaders(out_modules);
}

[[nodiscard]] LosslessStatus ReadImageFile(const std::filesystem::path& path,
                                          std::vector<u8>& out_image) {
    if (!std::filesystem::exists(path)) {
        return LosslessStatus::NotInstalled;
    }

    FILE* file = std::fopen(path.string().c_str(), "rb");
    if (!file) {
        return LosslessStatus::UnreadableFile;
    }

    std::fseek(file, 0, SEEK_END);
    const long size = std::ftell(file);
    if (size <= 0) {
        std::fclose(file);
        return LosslessStatus::UnreadableFile;
    }
    std::fseek(file, 0, SEEK_SET);

    out_image.resize(static_cast<size_t>(size));
    if (std::fread(out_image.data(), 1, out_image.size(), file) != out_image.size()) {
        std::fclose(file);
        return LosslessStatus::UnreadableFile;
    }
    std::fclose(file);
    return LosslessStatus::Ok;
}

[[nodiscard]] LosslessStatus ParseShaderSpans(std::span<const u8> image,
                                              ResourceSpans& out_resources) {
    const ImageReader reader{image};
    const std::optional<size_t> pe_offset = FindPeHeader(reader);
    if (!pe_offset) {
        return LosslessStatus::NotPortableExecutable;
    }

    const std::optional<size_t> data_directory =
        FindDataDirectory(reader, *pe_offset + 4 + COFF_HEADER_SIZE);
    if (!data_directory) {
        return LosslessStatus::NotPortableExecutable;
    }

    std::vector<Section> sections;
    if (!ReadSections(reader, *pe_offset, sections)) {
        return LosslessStatus::NotPortableExecutable;
    }

    u32 resource_rva{};
    if (!reader.Read(*data_directory + RESOURCE_DATA_DIRECTORY_INDEX * DATA_DIRECTORY_ENTRY_SIZE,
                     resource_rva) ||
        resource_rva == 0) {
        return LosslessStatus::MissingShaders;
    }

    const std::optional<size_t> resource_base = RvaToFileOffset(sections, resource_rva);
    if (!resource_base) {
        return LosslessStatus::NotPortableExecutable;
    }

    out_resources.clear();
    if (!CollectRcData(reader, sections, *resource_base, out_resources)) {
        return LosslessStatus::MissingShaders;
    }

    if (!HasNativeShaders(out_resources)) {
        return LosslessStatus::MissingShaders;
    }
    return LosslessStatus::Ok;
}

static std::filesystem::path g_storage_root;
static bool g_frame_gen_enabled = false;
static int g_frame_gen_multiplier = 2;

} // Anonymous namespace

void SetStorageRoot(const std::string& path) {
    g_storage_root = path;
}

std::filesystem::path GetLosslessDllPath() {
    if (g_storage_root.empty()) {
        return std::filesystem::path("lossless") / LOSSLESS_DLL_FILE;
    }
    return g_storage_root / "lossless" / LOSSLESS_DLL_FILE;
}

std::filesystem::path GetShaderCachePath() {
    if (g_storage_root.empty()) {
        return std::filesystem::path("lossless") / LOSSLESS_CACHE_FILE;
    }
    return g_storage_root / "lossless" / LOSSLESS_CACHE_FILE;
}

LosslessStatus ReadShaderResources(const std::filesystem::path& path,
                                   ShaderResources& out_resources) {
    std::vector<u8> image;
    const LosslessStatus read_status = ReadImageFile(path, image);
    if (read_status != LosslessStatus::Ok) {
        return read_status;
    }

    ResourceSpans spans;
    const LosslessStatus parse_status = ParseShaderSpans(image, spans);
    if (parse_status != LosslessStatus::Ok) {
        return parse_status;
    }

    out_resources.clear();
    for (const auto& [id, data] : spans) {
        out_resources.emplace(id, std::vector<u8>{data.begin(), data.end()});
    }
    return LosslessStatus::Ok;
}

LosslessStatus ValidateLosslessDll(const std::filesystem::path& path) {
    std::vector<u8> image;
    const LosslessStatus read_status = ReadImageFile(path, image);
    if (read_status != LosslessStatus::Ok) {
        return read_status;
    }

    ResourceSpans spans;
    return ParseShaderSpans(image, spans);
}

LosslessStatus GetInstalledLosslessStatus() {
    return ValidateLosslessDll(GetLosslessDllPath());
}

LosslessStatus LoadShaderModules(ShaderModules& out_modules) {
    std::vector<u8> image;
    const LosslessStatus read_status = ReadImageFile(GetLosslessDllPath(), image);
    if (read_status != LosslessStatus::Ok) {
        return read_status;
    }

    const u64 source_size = image.size();
    const u64 source_hash =
        Common::CityHash64(reinterpret_cast<const char*>(image.data()), image.size());
    const std::filesystem::path cache_path = GetShaderCachePath();

    ResourceSpans spans;
    const LosslessStatus parse_status = ParseShaderSpans(image, spans);
    if (parse_status != LosslessStatus::Ok) {
        return parse_status;
    }

    if (ReadShaderCache(cache_path, source_size, source_hash, out_modules)) {
        return LosslessStatus::Ok;
    }

    const LosslessStatus translate_status = TranslateAll(spans, out_modules);
    if (translate_status != LosslessStatus::Ok) {
        return translate_status;
    }

    const CacheHeader header{
        .magic = CACHE_MAGIC,
        .version = CACHE_VERSION,
        .source_size = source_size,
        .source_hash = source_hash,
        .module_count = static_cast<u32>(out_modules.size()),
    };
    if (!WriteShaderCache(cache_path, header, out_modules)) {
        std::error_code ec;
        std::filesystem::remove(cache_path, ec);
        return LosslessStatus::CacheUnusable;
    }

    return LosslessStatus::Ok;
}

LosslessStatus BuildShaderCache() {
    ShaderModules modules;
    return LoadShaderModules(modules);
}

bool RemoveInstalledLosslessDll() {
    std::error_code ec;
    const std::filesystem::path cache_path = GetShaderCachePath();
    if (std::filesystem::exists(cache_path)) {
        std::filesystem::remove(cache_path, ec);
    }

    const std::filesystem::path path = GetLosslessDllPath();
    if (!std::filesystem::exists(path)) {
        return true;
    }
    return std::filesystem::remove(path, ec);
}

void SetFrameGenEnabled(bool enabled, int multiplier) {
    g_frame_gen_enabled = enabled;
    g_frame_gen_multiplier = multiplier;

    const std::string dll_path = GetLosslessDllPath().string();
    if (enabled) {
        setenv("LSFG_LEGACY", "1", 1);
        setenv("LSFG_DLL_PATH", dll_path.c_str(), 1);
        setenv("LSFG_DLL_PATH_UNIX", dll_path.c_str(), 1);
        setenv("LSFG_MULTIPLIER", std::to_string(multiplier).c_str(), 1);
        setenv("LSFG_PERFORMANCE_MODE", "1", 1);
        setenv("VK_INSTANCE_LAYERS", "VK_LAYER_LS_frame_generation", 1);

        void* hLayer = dlopen("liblsfg-vk.so", RTLD_NOW | RTLD_GLOBAL);
        if (!hLayer) {
            dlopen("libVkLayer_LS_frame_generation.so", RTLD_NOW | RTLD_GLOBAL);
        }
    } else {
        setenv("LSFG_MULTIPLIER", "1", 1);
        setenv("VK_INSTANCE_LAYERS", "", 1);
    }
}

bool IsFrameGenEnabled() {
    return g_frame_gen_enabled;
}

int GetFrameGenMultiplier() {
    return g_frame_gen_multiplier;
}

// ── Vulkan Capability Check ──────────────────────────────────────────────────

static GpuDeviceInfo s_gpu_device_info{
    .deviceName = "Vulkan Compatible GPU",
    .driverVersion = "",
    .apiVersion = "1.3.0",
    .hasFloat16 = true,
    .hasVulkanMemoryModel = true,
    .isSupported = true,
};
static std::mutex s_gpu_info_mutex;

void SetGpuDeviceInfo(const std::string& name, const std::string& api, const std::string& driver, bool supported) {
    std::lock_guard<std::mutex> lock(s_gpu_info_mutex);
    if (!name.empty()) {
        s_gpu_device_info.deviceName = name;
    }
    if (!api.empty()) {
        s_gpu_device_info.apiVersion = api;
    }
    if (!driver.empty()) {
        s_gpu_device_info.driverVersion = driver;
    }
    s_gpu_device_info.isSupported = supported;
}

GpuDeviceInfo QueryGpuDeviceInfo() {
    std::lock_guard<std::mutex> lock(s_gpu_info_mutex);
    return s_gpu_device_info;
}

bool GetFrameGenerationSupport() {
    std::lock_guard<std::mutex> lock(s_gpu_info_mutex);
    return s_gpu_device_info.isSupported;
}

} // namespace VideoCore::FrameGen
