/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MpvScriptAssetsTest {
  @Test fun generatedButtonsDoNotInvalidateUserScriptInventory() = withFiles { root ->
    write(root, "scripts/user.lua", "-- user script")
    write(root, "scripts/helpers/module.lua", "return {}")
    val generated = MpvScriptAssets.generatedDirectory(root)
    generated.mkdirs()
    File(generated, "custombuttons.lua").writeText("-- generated button")
    File(generated, "custombuttons.js").writeText("// generated button")

    assertEquals(setOf("user.lua"), MpvScriptAssets.userScriptNames(root))
    assertTrue(generated.parentFile == root)
    assertFalse(generated == File(root, "scripts"))
  }

  @Test fun unchangedModulesAreNotRewritten() = withFiles { root ->
    val source = File(root, "scripts")
    val target = File(root, "script-modules")
    write(source, "helpers/nested/module.lua", "return { value = 1 }")
    write(source, "helpers/other.LUA", "return {}")
    write(source, "helpers/readme.txt", "helper assets")
    write(source, "entry.lua", "-- must not become a require module")

    assertEquals(2, MpvScriptAssets.syncLuaModules(source, target))
    val module = File(target, "helpers/nested/module.lua")
    val modified = module.lastModified()
    assertEquals(0, MpvScriptAssets.syncLuaModules(source, target))
    assertEquals(modified, module.lastModified())
    assertFalse(File(target, "helpers/readme.txt").exists())
    assertFalse(File(target, "entry.lua").exists())
  }

  @Test fun changedAndRemovedModulesAreReconciled() = withFiles { root ->
    val source = File(root, "scripts")
    val target = File(root, "script-modules")
    val changed = write(source, "helpers/module.lua", "return {}")
    val removed = write(source, "old/nested/removed.lua", "return {}")
    assertEquals(2, MpvScriptAssets.syncLuaModules(source, target))

    changed.writeText("return { updated = true }")
    assertTrue(removed.parentFile.parentFile.deleteRecursively())
    assertEquals(1, MpvScriptAssets.syncLuaModules(source, target))
    assertEquals(changed.readText(), File(target, "helpers/module.lua").readText())
    assertFalse(File(target, "old").exists())
  }

  @Test fun failedSourceInventoryDoesNotEraseExistingModules() = withFiles { root ->
    val target = File(root, "script-modules")
    val existing = write(target, "helpers/module.lua", "return {}")
    assertTrue(runCatching { MpvScriptAssets.syncLuaModules(File(root, "missing"), target) }.isFailure)
    assertEquals("return {}", existing.readText())
  }

  private fun write(root: File, path: String, content: String): File = File(root, path).also {
    it.parentFile?.mkdirs()
    it.writeText(content)
  }

  private fun withFiles(block: (File) -> Unit) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = File(context.cacheDir, "mpv-script-assets-${UUID.randomUUID()}")
    check(root.mkdirs())
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }
}
