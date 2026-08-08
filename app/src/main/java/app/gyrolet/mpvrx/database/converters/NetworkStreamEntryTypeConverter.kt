/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.database.converters

import androidx.room.TypeConverter
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryType

class NetworkStreamEntryTypeConverter {
  @TypeConverter
  fun fromEntryType(type: NetworkStreamEntryType): String = type.name

  @TypeConverter
  fun toEntryType(value: String): NetworkStreamEntryType = NetworkStreamEntryType.valueOf(value)
}
