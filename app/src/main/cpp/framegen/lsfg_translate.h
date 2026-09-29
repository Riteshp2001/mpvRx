// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <span>
#include <vector>

#include "common_types.h"

namespace VideoCore::FrameGen {

[[nodiscard]] bool IsSpirvModule(std::span<const u8> blob);

[[nodiscard]] std::vector<u32> AdoptSpirvModule(std::span<const u8> blob);

} // namespace VideoCore::FrameGen
