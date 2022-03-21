package osprey

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.kotlin.dsl.*
import org.json.JSONObject
import java.nio.file.Path
import kotlin.streams.asSequence
import org.jetbrains.dokka.gradle.DokkaTask

import osprey.build.*
import java.io.File
import java.net.URL
import java.net.URLClassLoader


// is there a docDir in the house??
val Project.docDir get() = projectPath / "doc"
val Project.docMainDir  get() = docDir / "content/documentation/main"

const val docReleaseName = "osprey-docs"


fun Project.makeDocsTasks() {

	val buildDocDir = buildPath / "doc"
	val dokkaCacheDir = buildDocDir / "dokkaCache"

	// weirdly enough, this one needs to be created at config time
	dokkaCacheDir.createFolderIfNeeded()

	val javadocJsonFile = buildDocDir / "javadoc.json"
	val dokkaJsonFile = buildDocDir / "kdoc" / "kdoc.json"


	val parseJavadoc by tasks.creating {
		group = "documentation"
		description = "export javadocs into a queryable format"

		// set up inputs/outputs for incremental builds
		inputs.files(fileTree(sourceSets.main.java.sourceDirectories.first()))
		outputs.file(javadocJsonFile)

		doLast {

			javadocJsonFile.parent.createFolderIfNeeded()
			javadocJsonFile.write {

				val json = JSONObject()
				JavadocTool.runFolder( // IntelliJ is wrong here, gradle doesn't think this is an error
					Jvm.packagePath.replace('/', '.'),
					sourceSets.main.java.sourceDirectories.first().absoluteFile.toPath(),
					json
				)
				write(json.toString(2))
			}
		}
	}

	val parseKdoc by tasks.creating(DokkaTask::class) {
		group = "documentation"
		description = "export dokka into a queryable format"

		val dir = buildDocDir / "kdoc"
		outputDirectory.set(dir.toFile())

		moduleName.set(Jvm.packagePath.replace('/', '.'))

		// the default is apparently in the home directory, so override that obviously
		cacheRoot.set(dokkaCacheDir.toFile())

		dokkaSourceSets {
			configureEach {
				// by default, dokka wants to analyze the java code too
				// so just set the kotlin source folder
				sourceRoots.setFrom(kotlin.sourceSets.main.kotlin.srcDirs.first().absolutePath)
			}
		}

		// tell dokka where to find our plugin, and its dependencies
		dependencies {

			// tragically, there seems to be no way to ask gradle what the buildSrc dependencies are
			// but we can just ask the class loader
			val classpathFiles = (OspreyDokkaPlugin::class.java.classLoader as URLClassLoader)
				.urLs
				.map { File(it.file) }

			// the buildSrc jar has our plugin itself in it
			val buildSrcJar = classpathFiles.first { it.name == "buildSrc.jar" }
			plugins(files(buildSrcJar))

			// and we'll have to pull out the dependencies manually
			val jsonJar = classpathFiles.first { it.name.startsWith("json-") }
			plugins(files(jsonJar))

			// NOTE: dokka complains if you put its own jars on the plugin classpath,
			// so we can't just use the whole buildSrc classpath
		}

		// send arguments to our plugin, json-style
		pluginsMapConfiguration.set(mapOf(
			OspreyDokkaPlugin::class.qualifiedName to """
				|{
				|	filename: '${dokkaJsonFile.fileName}',
				|	package: '${Jvm.packagePath.replace('/', '.')}'
				|}
			""".trimMargin()
		))
	}

	val generatePythonDocs by tasks.creating {
		group = "documentation"

		val dir = buildDocDir / "code-python"

		dependsOn(parseJavadoc, parseKdoc)
		inputs.files(javadocJsonFile)
		inputs.files(dokkaJsonFile)
		outputs.dir(dir)

		doLast {

			// generate the documentation into a build sub-folder
			dir.recreateFolder()

			// render python docs
			val modules = listOf(
				"osprey",
				"osprey.prep",
				"osprey.ccs",
				"osprey.slurm",
				"osprey.jvm"
			)
			for ((modulei, module) in modules.withIndex()) {
				pydocMarkdown(module, dir / "$module.md", weight = modulei + 1)
			}
		}
	}

	val generateJavaDocs by tasks.creating {
		group = "documentation"

		val dir = buildDocDir / "code-java"

		// set up inputs/outputs for incremental builds
		inputs.files(fileTree(sourceSets.main.java.sourceDirectories.first()))
		outputs.dir(dir)

		doLast {

			dir.recreateFolder()

			// render java docs, see:
			// https://docs.oracle.com/en/java/javase/17/docs/specs/man/javadoc.html
			exec {
				commandLine(
					"javadoc",
					"-source", Jvm.javaLangVersion.toString(),
					"-sourcepath", sourceSets.main.java.srcDirs.first().absolutePath,
					"-d", dir.toString(),
					"-subpackages", Jvm.packagePath.replace('/', '.'),
					"-classpath", sourceSets.main.runtimeClasspath.joinToClasspath { it.absolutePath },
					*Jvm.moduleArgs.toTypedArray(),
					"-Xdoclint:none"
				)
			}

			// hugo wants to use the index.html url,
			// so rename the index file generated by javadoc to something else
			dir.resolve("index.html").rename("start.html")

			// tweak the markdown files from the javadoc folder, otherwise hugo get confused
			dir.walk { stream ->
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

	val generateKotlinDocs by tasks.creating(DokkaTask::class) {
		group = "documentation"

		val outDir = buildDocDir / "code-kotlin"
		doFirst {
			outDir.createFolderIfNeeded()
		}
		outputDirectory.set(outDir.toFile())

		moduleName.set(Jvm.packagePath.replace('/', '.'))

		// the default is apparently in the home directory, so override that obviously
		cacheRoot.set(dokkaCacheDir.toFile())

		dokkaSourceSets {
			configureEach {
				// by default, dokka wants to analyze the java code too
				// so just set the kotlin source folder
				sourceRoots.setFrom(kotlin.sourceSets.main.kotlin.srcDirs.first().absolutePath)
			}
		}

		doLast {
			// hugo wants to use the index.html url,
			// so rename the index file generated by dokka to something else
			(outDir / "index.html").rename("start.html")
		}
	}

	val generateCodeDocs by tasks.creating {
		group = "documentation"
		description = "Generate the Python, Java, and Kotlin code documentation for the current source tree"
		dependsOn(generatePythonDocs, generateJavaDocs, generateKotlinDocs)
	}

	fun checkHugoPrereqs() {

		// commands we'll need
		commandExistsOrThrow("hugo")
		commandExistsOrThrow("git")

		// download the theme, if needed
		val themeDir = docDir / "themes" / "hugo-theme-learn"
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
		}

		// make sure we got it
		if (!themeDir.exists()) {
			throw Error("Hugo theme is not available. The download must have failed somehow.")
		}
	}

	val buildDocsRelease by tasks.creating(Tar::class) {
		group = "documentation"
		description = "Builds the code documention archive for the current version of Osprey"
		dependsOn(generateCodeDocs)

		val versionStr = project.version.toString()

		archiveBaseName.set(docReleaseName)
		archiveVersion.set(versionStr)
		destinationDirectory.set(releasesDir.toFile())
		compression = Compression.BZIP2

		// compute a sorting weight from the version number
		// newest versions should be at the top
		// meaning, weights for newer versions should be smaller than weights for older versions
		// the weight 1 is reserved for the main branch
		val version = Version.of(versionStr)
		var weight = 100_000_000
		weight -= version.major*1_000_000
		weight -= version.minor*1_000
		weight -= version.minor

		into("content/documentation/v$versionStr/") {
			into ("code/python") {
				from(buildPath / "doc/code-python")
			}
			into ("code/java") {
				from(buildPath / "doc/code-java")
			}
			into ("code/kotlin") {
				from(buildPath / "doc/code-kotlin")
			}
			from(docDir / "content" / "documentation" / "main") {

				// copy the documentation/main folder, but rewrite the index.md file,
				// which has lots of references to the main branch that won't be appropriate here
				exclude("/_index.md")
			}
			from(docDir / "archetypes" / "doc-version.md") {
				rename("doc-version.md", "_index.md")
				expand(
					"versionStr" to versionStr,
					"weight" to "$weight"
				)
			}
		}
	}

	val downloadDocReleases by tasks.creating {
		group = "documentation"
		description = "Download all versions of the doc releases, for the website generator"
		doLast {
			ssh {
				sftp {

					// what releases do we have already?
					val localReleases = releasesDir.listFiles()
						.map { it.fileName.toString() }
						.filter { it.startsWith(docReleaseName) }
						.toSet()

					// what releases do we need?
					val missingReleases = ls(releaseArchiveDir.toString())
						.filter { !it.attrs.isDir }
						.filter { it.filename.startsWith(docReleaseName) && it.filename !in localReleases }

					// download the missing releases
					if (missingReleases.isNotEmpty()) {
						for (release in missingReleases) {
							get(
								(releaseArchiveDir / release.filename).toString(),
								(releasesDir / release.filename).toString(),
								SftpProgressLogger()
							)
						}
					} else {
						println("No extra documentation releases to download")
					}
				}
			}
		}
	}

	data class DocRelease(
		val version: Version,
		val path: Path
	)

	val buildWebsite by tasks.creating(Tar::class) {
		group = "documentation"
		description = "Builds the Osprey documentation and download website"
		dependsOn(generateCodeDocs, downloadDocReleases)

		val webDir = buildPath / "website-release"
		val srcDir = webDir / "src"
		val dstDir = webDir / "dst"

		doFirst {

			checkHugoPrereqs()

			webDir.recreateFolder()
			srcDir.createFolderIfNeeded()
			dstDir.createFolderIfNeeded()

			// copy over the docs from the source tree
			copy {
				from(docDir)
				into(srcDir)
			}

			// copy over the generated code docs
			val mainCodeDir = srcDir / "content" / "documentation" / "main" / "code"
			copy {
				from((buildDocDir / "code-java").toFile())
				into((mainCodeDir / "java").toFile())
			}
			copy {
				from((buildDocDir / "code-kotlin").toFile())
				into((mainCodeDir / "kotlin").toFile())
			}
			copy {
				from((buildDocDir / "code-python").toFile())
				into((mainCodeDir / "python").toFile())
			}

			// query for the available doc releases
			val docReleases = releasesDir.listFiles()
				.mapNotNull { path ->
					if (!path.fileName.toString().startsWith("$docReleaseName-")) {
						null
					} else {
						// parse the filename to get the version, eg:
						// osprey-docs-4.0.tbz2
						val (base, _) = path.baseAndExtension()
						val parts = base.split('-')
						DocRelease(
							Version.of(parts[2]),
							path
						)
					}
				}
				.toList()

			println("found documentation releases: ${docReleases.map { it.version }}")

			// unpack the docs releases
			for (release in docReleases) {
				copy {
					from(tarTree(release.path.toFile()))
					into(srcDir.toFile())
				}
			}

			// add version links to the versioned docs main page
			updateTags(srcDir / "content/documentation/_index.md",
				"doc/versions" to docReleases
					.map { release ->
						" * [v${release.version}](v${release.version})"
					}
					.joinToString("\n")
					.let { "\n\n$it\n" }
			)

			// generate the download links
			val releases = analyzeReleases()

			fun latestLink(build: Build, os: OS): String {

				val release = releases
					.filter { it.build === build && it.os == os }
					.maxByOrNull { it.version }!!

				val url = URL(releaseArchiveUrl, release.filename)

				return "[${release.filename}]($url)"
			}

			updateTags(srcDir / "content" / "_index.md",
				"download/desktop/linux/latest" to latestLink(Builds.desktop, OS.LINUX),
				"download/desktop/osx/latest" to latestLink(Builds.desktop, OS.OSX),
				"download/desktop/windows/latest" to latestLink(Builds.desktop, OS.WINDOWS),
				"download/server/linux/latest" to latestLink(Builds.server, OS.LINUX),
				"download/server/osx/latest" to latestLink(Builds.server, OS.OSX),
				"download/server/windows/latest" to latestLink(Builds.server, OS.WINDOWS),
				"download/service-docker/linux/latest" to latestLink(Builds.serviceDocker, OS.LINUX),
			)

			fun allLinks(build: Build, os: OS): String =
				releases
					.filter { it.build === build && it.os == os }
					.sortedBy { it.version }
					.map { release ->
						val url = URL(releaseArchiveUrl, release.filename)
						" * **v${release.version}**: [${release.filename}]($url)"
					}
					.joinToString("\n")
					.let { "\n\n$it\n" }

			updateTags(srcDir / "content" / "install" / "versions.md",
				"download/desktop/linux/all" to allLinks(Builds.desktop, OS.LINUX),
				"download/desktop/osx/all" to allLinks(Builds.desktop, OS.OSX),
				"download/desktop/windows/all" to allLinks(Builds.desktop, OS.WINDOWS),
				"download/server/linux/all" to allLinks(Builds.server, OS.LINUX),
				"download/server/osx/all" to allLinks(Builds.server, OS.OSX),
				"download/server/windows/all" to allLinks(Builds.server, OS.WINDOWS),
				"download/serviceDocker/linux/all" to allLinks(Builds.serviceDocker, OS.LINUX)
			)

			// build the website using hugo
			exec {
				commandLine(
					"hugo",
					"--destination", dstDir.toString()
				)
				workingDir = srcDir.toFile()
			}
		}

		destinationDirectory.set(buildDocDir.toFile())
		archiveBaseName.set("osprey-website")
		compression = Compression.GZIP

		from(dstDir)
	}

	@Suppress("UNUSED_VARIABLE")
	val deployWebsite by tasks.creating {
		group = "documentation"
		description = "Replace the documentation website with the one currently in the build folder"
		doLast {

			// first, make sure we have the website tar file
			val tarPath = buildWebsite.outputs.files.first().toPath()
			if (!tarPath.exists()) {
				throw Error("Create the website with the `${buildWebsite.name}` task first")
			}

			val tarPathRemote = websiteDeployDir / tarPath.fileName

			ssh {

				// upload the tar file
				sftp {
					put(
						tarPath.toString(),
						tarPathRemote.toString(),
						SftpProgressLogger()
					)
				}

				// extract the tar file
				println("Extracting website ...")
				exec("tar --extract -f \"$tarPathRemote\" --directory \"$websiteDeployDir\"")

				// all done!
				println("""
					|
					|Website deployed successfully!
					|You can visit the website at:
					|
					|https://www2.cs.duke.edu/donaldlab/software/osprey/docs/
					|
				""".trimMargin())
			}
		}
	}

	@Suppress("UNUSED_VARIABLE")
	val hugoServer by tasks.creating {
		group = "documentation"
		description = "Start the Hugo development server. Useful for getting quick feedback when writing and editing documentation"
		doLast {

			checkHugoPrereqs()

			println("""
				|
				|
				|NOTE:
				|The Huge dev server is useful for editing hand-written documentation,
				|but none of the automatically generated parts will be available there.
				|Meaning the code documentation, version history, and download links
				|will not appear while editing your documents.
				|
				|
			""".trimMargin());

			// TODO: any way to make the auto-generated bits show up in dev server mode?
			//   hugo's only modularity system is the go modules, which are a huge pain
			//   and have tradeoffs to deal with too

			val webDir = buildPath / "website-dev"

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
				projectPath / "buildSrc/src/main/python"
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
