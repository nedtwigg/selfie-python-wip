/*
 * Copyright (C) 2023 DiffPlug
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
package com.diffplug.selfie.junit5

import io.kotest.matchers.shouldBe
import java.io.StringReader
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.gradle.internal.impldep.junit.framework.AssertionFailedError
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

open class Harness(subproject: String) {
  val subprojectFolder: Path

  init {
    var rootFolder = FileSystem.SYSTEM.canonicalize("".toPath())
    if (!FileSystem.SYSTEM.exists(rootFolder.resolve("settings.gradle"))) {
      check(FileSystem.SYSTEM.exists(rootFolder.parent!!.resolve("settings.gradle"))) {
        "The root folder must contain settings.gradle, was not present in\n" +
            "  $rootFolder\n" +
            "  ${rootFolder.parent}"
      }
      rootFolder = rootFolder.parent!!
    }
    subprojectFolder = rootFolder.resolve(subproject)
    check(FileSystem.SYSTEM.exists(subprojectFolder)) {
      "The subproject folder $subproject must exist"
    }
  }
  protected fun ut_mirror() = file("UT_${javaClass.simpleName}.kt")
  protected fun ut_snapshot() = file("UT_${javaClass.simpleName}.ss")
  fun file(nameOrSubpath: String): FileHarness {
    return if (nameOrSubpath.contains('/')) {
      FileHarness(nameOrSubpath)
    } else {
      val matches =
          FileSystem.SYSTEM.listRecursively(subprojectFolder)
              .filter { it.name == nameOrSubpath && !it.toString().contains("build") }
              .toList()
      when (matches.size) {
        0 -> FileHarness(nameOrSubpath)
        1 -> FileHarness(matches[0].relativeTo(subprojectFolder).toString())
        else -> {
          val allMatches = matches.map { it.relativeTo(subprojectFolder) }.joinToString("\n  ")
          throw AssertionError(
              "Expected to find exactly one file named $nameOrSubpath, but found ${matches.size}:\n  $allMatches")
        }
      }
    }
  }

  inner class FileHarness(val subpath: String) {
    fun assertDoesNotExist() {
      if (FileSystem.SYSTEM.exists(subprojectFolder.resolve(subpath))) {
        throw AssertionError("Expected $subpath to not exist, but it does")
      }
    }
    fun deleteIfExists() {
      if (FileSystem.SYSTEM.exists(subprojectFolder.resolve(subpath))) {
        FileSystem.SYSTEM.delete(subprojectFolder.resolve(subpath))
      }
    }
    fun assertContent(expected: String) {
      FileSystem.SYSTEM.read(subprojectFolder.resolve(subpath)) {
        val actual = readUtf8()
        actual shouldBe expected
      }
    }
    fun setContent(content: String) {
      FileSystem.SYSTEM.write(subprojectFolder.resolve(subpath)) { writeUtf8(content) }
    }
    fun linesFrom(start: String): LineRangeSelector {
      FileSystem.SYSTEM.read(subprojectFolder.resolve(subpath)) {
        val allLines = mutableListOf<String>()
        while (!exhausted()) {
          readUtf8Line()?.let { allLines.add(it) }
        }
        val matchingLines =
            allLines.mapIndexedNotNull() { index, line ->
              if (line.contains(start)) "L$index: $line" else null
            }
        if (matchingLines.size == 1) {
          val idx = matchingLines[0].substringAfter("L").substringBefore(":").toInt()
          return LineRangeSelector(allLines, idx)
        } else {
          throw AssertionError(
              "Expected to find exactly one line containing $start in $subpath, but found ${matchingLines.size}:\n  ${matchingLines.joinToString("\n  ")}")
        }
      }
    }
    fun lineWith(contains: String): LineRange {
      val selector = linesFrom(contains)
      return LineRange(selector.lines, selector.start, selector.start)
    }

    inner class LineRangeSelector(val lines: MutableList<String>, val start: Int) {
      fun toFirst(end: String): LineRange {
        val idx = lines.subList(start + 1, lines.size).indexOfFirst { it.contains(end) }
        return tryReturn(end, start + 1 + idx)
      }
      fun toLast(end: String): LineRange {
        val idx = lines.indexOfLast { it.contains(end) }
        return tryReturn(end, idx)
      }
      private fun tryReturn(end: String, idx: Int): LineRange {
        if (idx < start) {
          throw AssertionError(
              "There are no lines containing '$end' after L$start: ${lines[start]}")
        }
        return LineRange(lines, start, idx)
      }
    }

    inner class LineRange(
        val lines: MutableList<String>,
        val startInclusive: Int,
        val endInclusive: Int
    ) {
      init {
        assert(startInclusive >= 0)
        assert(endInclusive >= startInclusive)
        assert(lines.size >= endInclusive)
      }
      fun shrinkByOne() = LineRange(lines, startInclusive + 1, endInclusive - 1)
      /** Prepend `//` to every line in this range and save it to disk. */
      fun commentOut() = mutateLinesAndWriteToDisk { lineNumber, line ->
        if (line.trim().startsWith("//")) {
          line
        } else "//$line"
      }
      fun uncomment() = mutateLinesAndWriteToDisk { lineNumber, line ->
        if (line.trim().startsWith("//")) {
          line.trim().substring(2)
        } else line
      }
      fun assertCommented(isCommented: Boolean) = mutateLinesAndWriteToDisk { lineNumber, line ->
        assert(line.trim().isEmpty() || line.startsWith("//") == isCommented) {
          "Expected L$lineNumber to ${if (isCommented) "be" else "not be"} commented, was $line"
        }
        line
      }
      private inline fun mutateLinesAndWriteToDisk(mutator: (Int, String) -> String) {
        for (i in startInclusive..endInclusive) {
          lines[i] = mutator(i + 1, lines[i])
        }
        FileSystem.SYSTEM.write(subprojectFolder.resolve(subpath)) {
          for (line in lines) {
            writeUtf8(line)
            writeUtf8("\n")
          }
        }
      }
    }
  }
  fun gradlew(task: String, vararg args: String): AssertionFailedError? {
    val runner =
        GradleConnector.newConnector()
            .forProjectDirectory(subprojectFolder.parent!!.toFile())
            .connect()

    try {
      val buildLauncher =
          runner
              .newBuild()
              .forTasks(":${subprojectFolder.name}:$task")
              .withArguments(
                  buildList<String> {
                    addAll(args)
                    add("--configuration-cache") // ControlWR enabled vs disables is 11s vs 24s
                    add("--stacktrace")
                  })
      buildLauncher.run()
      return null
    } catch (e: BuildException) {
      return parseBuildException(task)
    }
  }

  /**
   * Parse build exception from gradle by looking into <code>build</code> directory to the matching
   * test.
   *
   * Parses the exception message as well as stacktrace.
   */
  private fun parseBuildException(task: String): AssertionFailedError {
    val xmlFile =
        subprojectFolder
            .resolve("build")
            .resolve("test-results")
            .resolve(task)
            .resolve("TEST-" + task.lowercase() + ".junit5.UT_" + javaClass.simpleName + ".xml")
    val xml = FileSystem.SYSTEM.read(xmlFile) { readUtf8() }
    val dbFactory = DocumentBuilderFactory.newInstance()
    val dBuilder = dbFactory.newDocumentBuilder()
    val xmlInput = InputSource(StringReader(xml))
    val doc = dBuilder.parse(xmlInput)
    val xpFactory = XPathFactory.newInstance()
    val xPath = xpFactory.newXPath()

    // <item type="T1" count="1">Value1</item>
    val xpath = "/testsuite/testcase/failure"
    val failures = xPath.evaluate(xpath, doc, XPathConstants.NODESET) as NodeList
    val failure = failures.item(0)
    val type = failure.attributes.getNamedItem("type").nodeValue
    val message = failure.attributes.getNamedItem("message").nodeValue.replace("&#10;", "\n")
    val lines = failure.textContent.replace(message, "").trim().split("\n")
    val stacktrace: MutableList<StackTraceElement> = ArrayList()
    val tracePattern =
        Pattern.compile("\\s*at\\s+([\\w]+)//([\\w\\.]+)\\.([\\w]+)(\\(.*kt)?:(\\d+)\\)")
    lines.forEach {
      val traceMatcher: Matcher = tracePattern.matcher(it)
      while (traceMatcher.find()) {
        val module: String = traceMatcher.group(1)
        val className: String = module + "//" + traceMatcher.group(2)
        val methodName: String = traceMatcher.group(3)
        val sourceFile: String = traceMatcher.group(4)
        val lineNum: Int = traceMatcher.group(5).toInt()
        stacktrace.add(StackTraceElement(className, methodName, sourceFile, lineNum))
      }
    }
    val error = AssertionFailedError(message.replace("$type: ", "").trim())
    error.stackTrace = stacktrace.toTypedArray()
    return error
  }
  fun gradleWriteSS() {
    gradlew("underTest", "-Pselfie=write")?.let {
      throw AssertionError("Expected write snapshots to succeed, but it failed", it)
    }
  }
  fun gradleReadSS() {
    gradlew("underTest", "-Pselfie=read")?.let {
      throw AssertionError("Expected read snapshots to succeed, but it failed", it)
    }
  }
  fun gradleReadSSFail(): AssertionFailedError {
    val failure = gradlew("underTest", "-Pselfie=read")
    if (failure == null) {
      throw AssertionError("Expected read snapshots to fail, but it succeeded.")
    } else {
      return failure
    }
  }
}