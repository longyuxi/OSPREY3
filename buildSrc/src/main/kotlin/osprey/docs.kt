package osprey

import org.gradle.api.Project
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.getValue
import java.nio.file.Path
import kotlin.streams.asSequence

import osprey.build.*
import java.net.URL


// is there a docDir in the house??
val Project.docDir get() = projectPath / "doc"
val Project.docMainDir  get() = docDir / "content/documentation/main"
val Project.pluginPath get() = buildPath / "classes/kotlin/test/META-INF/services/org.jetbrains.dokka.plugability.DokkaPlugin"


fun Project.makeDocsTasks() {

	val javadocJsonFile = buildPath / "doc/javadoc.json"

	val parseJavadoc by tasks.creating {
		group = "documentation"
		description = "export javadocs into a queryable format"
		dependsOn("testClasses")
		// NOTE: this task apparently won't re-run after a code recompile
		// my gradle-fu isn't good enough to figure out how to do that
		// in the meantime, just delete the build/doc/javadoc.json file to get this task to run again
		doLast {

			javadocJsonFile.parent.createFolderIfNeeded()
			javadocJsonFile.write { json ->

				javaexec {
					classpath = sourceSets.test.runtimeClasspath
					mainClass.set("build.JavadocTool")
					jvmArgs(
						*Jvm.moduleArgs.toTypedArray()
					)
					args(
						Jvm.packagePath.replace('/', '.'),
						sourceSets.main.java.sourceDirectories.first().absolutePath
					)
					standardOutput = json
				}
			}
		}
		outputs.file(javadocJsonFile)
	}

	val dokkaJsonFile = buildPath / "doc/kdoc.json"

	val parseKdoc by tasks.creating {
		group = "documentation"
		description = "export dokka into a queryable format"
		dependsOn("testClasses")
		doLast {

			val testDir = buildPath / "classes/kotlin/test"

			// install the dokka plugin
			val pluginName = "build.OspreyPlugin"
			pluginPath.parent.createFolderIfNeeded()
			pluginPath.write {
				write(pluginName)
			}

			dokkaJsonFile.parent.createFolderIfNeeded()

			// use a plugin to make Dokka render to a JSON file
			// https://kotlin.github.io/dokka/1.6.0/developer_guide/introduction/
			javaexec {
				mainClass.set("org.jetbrains.dokka.MainKt")
				setClasspath(sourceSets.test.runtimeClasspath)
				args(
					"-moduleName", Jvm.packagePath.replace('/', '.'),
					"-outputDir", dokkaJsonFile.parent.toString(),
					"-sourceSet", listOf(
						"-src", kotlin.sourceSets.main.kotlin.srcDirs.first().absolutePath,
						"-classpath", sourceSets.main.runtimeClasspath.joinToString(";") { it.absolutePath }
					).joinToString(" "),
					"-pluginsClasspath", listOf(
						// add the build classes folder, so Dokka will pick up our plugin
						testDir.toFile()
					).joinToString(";") { it.absolutePath },
					// NOTE: dokka always expects ; as the path separator, regardless of platform
					"-pluginsConfiguration", """
						|$pluginName={
						|	filename: '${dokkaJsonFile.fileName}',
						|	package: '${Jvm.packagePath.replace('/', '.')}'
						|}
					""".trimMargin()
				)
			}
		}
		outputs.file(dokkaJsonFile)
	}

	val generatePythonDocs by tasks.creating {
		group = "documentation"
		dependsOn(parseJavadoc, parseKdoc)
		inputs.files(javadocJsonFile)
		doLast {

			// generate the documentation into a hugo module in the build folder
			val modDir = buildPath / "doc/code-python"
			modDir.recreateFolder()

			// init the hugo module
			exec {
				commandLine("hugo", "mod", "init", "code-python")
				workingDir = modDir.toFile()
			}
			val contentDir = modDir.resolve("content")
			contentDir.createFolderIfNeeded()

			// render python docs
			val modules = listOf(
				"osprey",
				"osprey.prep",
				"osprey.ccs",
				"osprey.slurm",
				"osprey.jvm"
			)
			for ((modulei, module) in modules.withIndex()) {
				pydocMarkdown(module, contentDir / "$module.md", weight = modulei + 1)
			}
		}
	}

	val generateJavaDocs by tasks.creating {
		group = "documentation"
		doLast {

			// generate the documentation into a hugo module in the build folder
			val modDir = buildPath / "doc/code-java"
			modDir.recreateFolder()

			// init the hugo module
			exec {
				commandLine("hugo", "mod", "init", "code-java")
				workingDir = modDir.toFile()
			}
			val contentDir = modDir / "content"
			contentDir.createFolderIfNeeded()

			// render java docs, see:
			// https://docs.oracle.com/en/java/javase/17/docs/specs/man/javadoc.html
			exec {
				commandLine(
					"javadoc",
					"-source", Jvm.javaLangVersion.toString(),
					"-sourcepath", sourceSets.main.java.srcDirs.first().absolutePath,
					"-d", contentDir.toString(),
					"-subpackages", Jvm.packagePath.replace('/', '.'),
					"-classpath", sourceSets.main.runtimeClasspath.joinToClasspath { it.absolutePath },
					*Jvm.moduleArgs.toTypedArray(),
					"-Xdoclint:none"
				)
			}

			// hugo wants to use the index.html url,
			// so rename the index file generated by javadoc to something else
			contentDir.resolve("index.html").rename("start.html")

			// tweak the markdown files from the javadoc folder, otherwise hugo get confused
			contentDir.walk { stream ->
				stream.asSequence()
					.filter { it.extension() == "md" }
					.forEach {
						val text = it.read()
						it.write {
							write(hugoFrontMatter(hidden = true))
							write(text)
						}
					}
			}
		}
	}

	val generateKotlinDocs by tasks.creating {
		group = "documentation"
		doLast {

			// generate the documentation into a hugo module in the build folder
			val modDir = buildPath / "doc/code-kotlin"
			modDir.recreateFolder()

			// init the hugo module
			exec {
				commandLine("hugo", "mod", "init", "code-kotlin")
				workingDir = modDir.toFile()
			}
			val contentDir = modDir.resolve("content")
			contentDir.createFolderIfNeeded()

			// uninstall the dokka plugin
			if (pluginPath.exists()) {
				pluginPath.deleteFile()
			}

			// render Kotlin docs, see:
			// https://kotlin.github.io/dokka/1.5.30/user_guide/cli/usage/
			// https://discuss.kotlinlang.org/t/problems-running-dokka-cli-1-4-0-rc-jar-from-the-command-line/18855
			javaexec {
				mainClass.set("org.jetbrains.dokka.MainKt")
				setClasspath(sourceSets.test.runtimeClasspath)
				args(
					"-moduleName", Jvm.packagePath.replace('/', '.'),
					"-outputDir", contentDir.toString(),
					"-sourceSet", listOf(
						"-src", kotlin.sourceSets.main.kotlin.srcDirs.first().absolutePath,
						"-classpath", sourceSets.main.runtimeClasspath.joinToString(";") { it.absolutePath }
					).joinToString(" ")
				)
			}

			// hugo wants to use the index.html url,
			// so rename the index file generated by dokka to something else
			(contentDir / "index.html").rename("start.html")
		}
	}

	@Suppress("UNUSED_VARIABLE")
	val generateCodeDocs by tasks.creating {
		group = "documentation"
		description = "Generate the Python, Java, and Kotlin code documentation for the current source tree"
		dependsOn(generatePythonDocs, generateJavaDocs, generateKotlinDocs)
	}

	fun checkHugoPrereqs() {

		// commands we'll need
		commandExistsOrThrow("hugo")
		commandExistsOrThrow("git")
		commandExistsOrThrow("go")

		// download the theme, if needed
		val themeDir = buildPath / "doc/hugo-theme-learn"
		if (!themeDir.exists()) {

			exec {
				commandLine(
					"git", "clone",
					"--depth", "1",
					"--branch", "2.5.0",
					"https://github.com/matcornic/hugo-theme-learn",
					themeDir.toString()
				)
			}

			// pretend the theme is a go module
			exec {
				commandLine("go", "mod", "init", "local.tld/hugo-theme-learn")
				workingDir = themeDir.toFile()
			}
		}

		// make sure we got it
		if (!themeDir.exists()) {
			throw Error("Hugo theme is not available. The download must have failed somehow.")
		}
	}

	val generateDownloadLinks by tasks.creating {
		group = "documentation"
		description = "Generates the download links in the documentation"
		doLast {

			val releases = analyzeReleases()

			fun latestLink(build: Build, os: OS): String {

				val release = releases
					.filter { it.build === build && it.os == os }
					.sortedBy { it.version }
					.last()

				val url = URL(releaseArchiveUrl, release.filename)

				return "[${release.filename}]($url)"
			}

			updateTags(docDir / "content" / "_index.md",
				"download/desktop/linux/latest" to latestLink(Builds.desktop, OS.LINUX),
				"download/desktop/osx/latest" to latestLink(Builds.desktop, OS.OSX),
				"download/desktop/windows/latest" to latestLink(Builds.desktop, OS.WINDOWS),
				"download/server/linux/latest" to latestLink(Builds.server, OS.LINUX),
				"download/server/osx/latest" to latestLink(Builds.server, OS.OSX),
				"download/server/windows/latest" to latestLink(Builds.server, OS.WINDOWS),
				"download/service-docker/linux/latest" to latestLink(Builds.serviceDocker, OS.LINUX),
			)

			// TODO: make a releases page with all the available releases?
		}
	}

	@Suppress("UNUSED_VARIABLE")
	val buildWebsite by tasks.creating {
		group = "documentation"
		description = "Builds the Osprey documentation and download website"
		dependsOn(generateDownloadLinks)
		doLast {

			checkHugoPrereqs()

			val webDir = buildPath / "website"
			webDir.recreateFolder()

			exec {
				commandLine(
					"hugo",
					"--destination", webDir.toString()
				)
				workingDir = docDir.toFile()
			}
		}
	}

	@Suppress("UNUSED_VARIABLE")
	val hugoServer by tasks.creating {
		group = "documentation"
		description = "Start the Hugo development server. Useful for writing documentation"
		doLast {

			checkHugoPrereqs()

			val webDir = buildPath / "website"

			exec {
				commandLine(
					"hugo",
					"server",
					"--destination", webDir.toString()
				)
				workingDir = docDir.toFile()
			}
		}
	}
}


/**
 * Hugo front matter, in TOML format, with learn theme extensions
 * https://gohugo.io/content-management/front-matter/
 * https://learn.netlify.app/en/cont/pages/#front-matter-configuration
 */
fun hugoFrontMatter(
	title: String? = null,
	menuTitle: String? = null,
	weight: Int = 4,
	disableToc: Boolean = false,
	hidden: Boolean = false
): String =
	"""
		|+++
		|${if (title != null) """title = "$title" """ else ""}
		|${if (menuTitle != null) """menuTitle = "$menuTitle" """ else ""}
		|weight = $weight
		|${if (disableToc) "disableToc = true" else ""}
		|${if (hidden) "hidden = true" else ""}
		|+++
		|
		|
	""".trimMargin()


fun Project.pydocMarkdown(module: String, file: Path, title: String = module, weight: Int = 5) {

	// is pydoc-markdown installed
	commandExistsOrThrow("pydoc-markdown")

	val configPath = docDir / "pydoc-markdown.yml"

	file.write { out ->

		// write the hugo front matter
		write(hugoFrontMatter(
			title,
			weight = weight,
			hidden = true
		))

		// flush buffers before pointing other external programs into this stream
		flush()

		// generate the markdown from the python module using pydoc-markdown
		// https://github.com/NiklasRosenstein/pydoc-markdown
		exec {
			commandLine(
				"pydoc-markdown",
				"--search-path", projectPath / "src/main/python",
				"--module", module,
				configPath.toString()
			)
			workingDir = projectDir
			standardOutput = out
			environment["PYTHONPATH"] = listOf(
				System.getenv("PYTHONPATH"),
				projectPath / "src/test/python"
			).joinToClasspath()
		}
	}
}


/**
 * Make a regex to pick out a single HTML span tag inside of a markdown document.
 *
 * Using RegEx to parse HTML dosen't work in general.
 * But in this specific case of picking out an isolated tag in a markdown document,
 * it'll work just fine! 8]
 *
 * Need to match, eg:
 * <span id="download/desktop/linux/latest"></span>
 * <span id="download/desktop/linux/latest">some non-HTML content here</span>
 */
val spanRegex = Regex("<span id=\"([^\"]+)\">[^<]*</span>")

/**
 * Rewrite the given file with the given span tag substitutions
 */
fun updateTags(path: Path, vararg substitutions: Pair<String,String>) {

	val subs = substitutions.associate { (id, sub) -> id to sub }

	var markdown = path.read()

	markdown = spanRegex.replace(markdown) r@{ match ->

		// find the substitution for this tag, if any
		val id = match.groups.get(1)?.value ?: return@r match.value
		val sub = subs[id] ?: return@r match.value

		return@r "<span id=\"$id\">$sub</span>"
	}

	// save the file
	path.write {
		write(markdown)
	}
}
