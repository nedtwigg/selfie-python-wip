/*
 * Copyright (C) 2024 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.selfie.coroutines

import com.diffplug.selfie.Mode
import com.diffplug.selfie.Roundtrip
import com.diffplug.selfie.Selfie
import com.diffplug.selfie.Snapshot
import com.diffplug.selfie.guts.DiskStorage
import com.diffplug.selfie.guts.LiteralString
import com.diffplug.selfie.guts.LiteralValue
import com.diffplug.selfie.guts.TodoStub
import com.diffplug.selfie.guts.recordCall
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class CacheSelfieBinarySuspend<T>(
    private val disk: DiskStorage,
    private val roundtrip: Roundtrip<T, ByteArray>,
    private val generator: suspend () -> T
) {
  suspend fun toMatchDisk(sub: String = ""): T {
    return toMatchDiskImpl(sub, false)
  }
  suspend fun toMatchDisk_TODO(sub: String = ""): T {
    return toMatchDiskImpl(sub, true)
  }
  private suspend fun toMatchDiskImpl(sub: String, isTodo: Boolean): T {
    val call = recordCall(false)
    if (Selfie.system.mode.canWrite(isTodo, call, Selfie.system)) {
      val actual = generator()
      disk.writeDisk(Snapshot.of(roundtrip.serialize(actual)), sub, call)
      if (isTodo) {
        Selfie.system.writeInline(TodoStub.toMatchDisk.createLiteral(), call)
      }
      return actual
    } else {
      if (isTodo) {
        throw Selfie.system.fs.assertFailed(
            "Can't call `toMatchDisk_TODO` in ${Mode.readonly} mode!")
      } else {
        val snapshot =
            disk.readDisk(sub, call)
                ?: throw Selfie.system.fs.assertFailed(Selfie.system.mode.msgSnapshotNotFound())
        if (!snapshot.subject.isBinary || snapshot.facets.isNotEmpty()) {
          throw Selfie.system.fs.assertFailed(
              "Expected a binary subject with no facets, got ${snapshot}")
        }
        return roundtrip.parse(snapshot.subject.valueBinary())
      }
    }
  }
  private fun resolvePath(subpath: String) = Selfie.system.layout.rootFolder.resolveFile(subpath)
  suspend fun toBeFile_TODO(subpath: String): T {
    return toBeFileImpl(subpath, true)
  }
  suspend fun toBeFile(subpath: String): T {
    return toBeFileImpl(subpath, false)
  }
  private suspend fun toBeFileImpl(subpath: String, isTodo: Boolean): T {
    val call = recordCall(false)
    val writable = Selfie.system.mode.canWrite(isTodo, call, Selfie.system)
    if (writable) {
      val actual = generator()
      if (isTodo) {
        Selfie.system.writeInline(TodoStub.toBeFile.createLiteral(), call)
      }
      Selfie.system.fs.fileWriteBinary(resolvePath(subpath), roundtrip.serialize(actual))
      return actual
    } else {
      if (isTodo) {
        throw Selfie.system.fs.assertFailed("Can't call `toBeFile_TODO` in ${Mode.readonly} mode!")
      } else {
        return roundtrip.parse(Selfie.system.fs.fileReadBinary(resolvePath(subpath)))
      }
    }
  }
  suspend fun toBeBase64_TODO(unusedArg: Any? = null): T {
    return toBeBase64Impl(null)
  }
  suspend fun toBeBase64(snapshot: String): T {
    return toBeBase64Impl(snapshot)
  }

  @OptIn(ExperimentalEncodingApi::class)
  private suspend fun toBeBase64Impl(snapshot: String?): T {
    val call = recordCall(false)
    val writable = Selfie.system.mode.canWrite(snapshot == null, call, Selfie.system)
    if (writable) {
      val actual = generator()
      val base64 = Base64.Mime.encode(roundtrip.serialize(actual)).replace("\r", "")
      Selfie.system.writeInline(LiteralValue(snapshot, base64, LiteralString), call)
      return actual
    } else {
      if (snapshot == null) {
        throw Selfie.system.fs.assertFailed("Can't call `toBe_TODO` in ${Mode.readonly} mode!")
      } else {
        return roundtrip.parse(Base64.Mime.decode(snapshot))
      }
    }
  }
}
