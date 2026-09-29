// SPDX-FileCopyrightText: 2011 Google, Inc.
// SPDX-License-Identifier: MIT

#pragma once

#include <cstddef>
#include "common_types.h"

namespace Common {

[[nodiscard]] u64 CityHash64(const char* buf, size_t len);
[[nodiscard]] u64 CityHash64WithSeed(const char* buf, size_t len, u64 seed);
[[nodiscard]] u64 CityHash64WithSeeds(const char* buf, size_t len, u64 seed0, u64 seed1);

[[nodiscard]] inline u64 Hash128to64(const u128& x) {
    const u64 mul = 0x9ddfea08eb382d69ULL;
    u64 a = (x[0] ^ x[1]) * mul;
    a ^= (a >> 47);
    u64 b = (x[1] ^ a) * mul;
    b ^= (b >> 47);
    b *= mul;
    return b;
}

} // namespace Common
