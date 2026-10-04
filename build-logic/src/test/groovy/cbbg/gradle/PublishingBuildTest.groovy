package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

import static org.junit.jupiter.api.Assertions.*

class PublishingBuildTest {
    @TempDir File directory

    @ParameterizedTest
    @ValueSource(strings = ['26.1', '26.3'])
    void uploadRequestUsesAcceptedFileTypes(String minecraft) {
        Map fixture = CandidateFixture.create(directory, true, true, minecraft,
                minecraft == '26.1' ? '>=26.1 <26.2' : null)
        if (minecraft == '26.1') {
            fixture.target.compatibleMinecraft = ['26.1.1', '26.1.2']
            for (String patch : ['26.1.1', '26.1.2']) {
                Map specification = fixture.target.findAll { key, value ->
                    !(key in ['compatibleMinecraft', 'minecraftDependency'])
                }
                fixture.catalog.targets.add(specification + [id: patch + '-fabric', minecraft: patch,
                                                            artifactOf: fixture.target.id])
                Map alias = CandidateFiles.parse(new StringReader(JsonOutput.toJson(fixture.record))) as Map
                alias.id = patch + '-fabric'
                fixture.manifest.selected_targets.add(alias.id)
                fixture.manifest.targets.add(alias)
            }
            new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
            File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
            catalog.text = JsonOutput.toJson(fixture.catalog)
            fixture.manifest.targets.each { it.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog) }
            fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            CandidateFixture.checksums(fixture.bundle)
        }
        ReleaseEvidence.assemble(new CandidateManifest(fixture.file), fixture.target.id)
        File metadata = new File(directory, 'publication.json')
        Map publication = Publication.metadata(fixture.file, fixture.root, 'Release notes')
        metadata.text = JsonOutput.toJson(publication)
        File project = new File(fixture.root, 'build-config/publishing')
        project.mkdirs()
        new File(project, 'settings.gradle').text = '''pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
rootProject.name = 'publishing-test'
'''
        AtomicReference<Map> request = new AtomicReference<>()
        AtomicReference<Map> uploaded = new AtomicReference<>()
        AtomicReference<Throwable> failure = new AtomicReference<>()
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/v2') { exchange ->
            int status = 200
            Map response
            try {
                if (exchange.requestMethod == 'POST' && exchange.requestURI.path == '/v2/version') {
                    Map parts = multipart(exchange.requestBody.bytes, exchange.requestHeaders.getFirst('Content-Type'))
                    Map data = (Map) new JsonSlurper().parseText(new String(parts.remove('data') as byte[], 'UTF-8'))
                    request.set([data: data, files: parts])
                    data.file_types.each { name, type ->
                        if (type == 'signature' && !(name.tokenize('.').last() in ['asc', 'gpg', 'sig'])) {
                            throw new IllegalArgumentException('File extension ' + name.tokenize('.').last() +
                                    ' is invalid for input file')
                        }
                    }
                    response = data + [id: 'Version1', status: 'listed', date_published: '2026-10-05T00:00:00Z',
                                       files: parts.collect { name, bytes ->
                                           [filename: name, primary: name == data.primary_file,
                                            file_type: data.file_types[name],
                                            hashes: [sha512: MessageDigest.getInstance('SHA-512')
                                                    .digest((byte[]) bytes).encodeHex().toString()]]
                                       }]
                    uploaded.set(response)
                    status = 201
                } else if (exchange.requestMethod == 'GET' &&
                        exchange.requestURI.path in ['/v2/project/UBlXUQbC/check', '/v2/project/fabric-api/check']) {
                    response = [id: exchange.requestURI.path.contains('/fabric-api/') ? 'P7dR8mSH' : 'UBlXUQbC']
                } else {
                    throw new IllegalArgumentException('Unexpected API request: ' + exchange.requestURI)
                }
            } catch (Throwable problem) {
                failure.set(problem)
                status = 400
                response = [error: 'invalid_input', description: problem.message]
            }
            byte[] body = JsonOutput.toJson(response).getBytes('UTF-8')
            exchange.responseHeaders.set('Content-Type', 'application/json')
            exchange.responseHeaders.set('X-Ratelimit-Limit', '300')
            exchange.responseHeaders.set('X-Ratelimit-Remaining', '299')
            exchange.responseHeaders.set('X-Ratelimit-Reset', '60')
            exchange.sendResponseHeaders(status, body.length)
            exchange.responseBody.withCloseable { it.write(body) }
        }
        server.start()
        try {
            new File(project, 'build.gradle').text = new File('../build-config/publishing/build.gradle').text + '''
modrinth {
    apiUrl = 'http://127.0.0.1:''' + server.address.port + '''/v2'
    token = 'mrp_test'
}
tasks.register('sendUpload') {
    dependsOn 'verifyUploadInputs'
    doLast { tasks.named('modrinth').get().apply() }
}
'''
            GradleRunner.create().withProjectDir(project).withPluginClasspath().withArguments(
                    'sendUpload', '-Ptarget=' + fixture.target.id,
                    '-PpublicationMetadata=' + metadata.absolutePath, '-Pcandidate=' + fixture.file.absolutePath,
                    '-PsourceRoot=' + fixture.root.absolutePath, '--stacktrace').build()
            assertNull(failure.get())
            Map actual = request.get()
            assertNotNull(actual)
            Map record = publication.records.first()
            assertEquals(record.modrinth.version_number, actual.data.version_number)
            assertEquals(record.modrinth.game_versions, actual.data.game_versions)
            assertEquals(record.modrinth.loaders, actual.data.loaders)
            assertEquals(record.artifact.path, actual.data.primary_file)
            assertEquals(['sources-jar'], actual.data.file_types.values().toList())
            assertEquals(record.sources.path, actual.data.file_types.keySet().first())
            List<String> names = ['artifact', 'sources', 'evidence'].collect { record[it].path }
            assertEquals(names, actual.data.file_parts)
            assertEquals(names.toSet(), actual.files.keySet())
            names.each { name ->
                assertArrayEquals(new File(fixture.bundle, name).bytes, (byte[]) actual.files[name])
            }
            Map result = Publication.plan(fixture.file, metadata, fixture.target.id, fixture.root,
                    { String url ->
                        if (url.endsWith('/version/Version1')) return uploaded.get()
                        if (url.endsWith('/project/fabric-api')) return [id: 'P7dR8mSH']
                        if (url.endsWith('/project/UBlXUQbC')) return [id: 'UBlXUQbC', slug: 'cbbg']
                        fail('Unexpected verification request: ' + url)
                    }, null, 'Version1')
            assertEquals('reuse', result.action)
        } finally {
            server.stop(0)
        }
    }

    private static Map<String, byte[]> multipart(byte[] body, String contentType) {
        String boundary = contentType.split('boundary=', 2)[1].replace('"', '')
        Map<String, byte[]> parts = [:]
        new String(body, StandardCharsets.ISO_8859_1).split(Pattern.quote('--' + boundary)).each { part ->
            int separator = part.indexOf('\r\n\r\n')
            if (separator >= 0) {
                def name = part.substring(0, separator) =~ /name="([^"]+)"/
                if (!name.find()) throw new IllegalArgumentException('Multipart part has no name')
                parts[name.group(1)] = part.substring(separator + 4, part.length() - 2)
                        .getBytes(StandardCharsets.ISO_8859_1)
            }
        }
        parts
    }

    @Test void sharedArtifactCanBeValidatedAndPlannedOnlyThroughItsOwner() {
        Map fixture = CandidateFixture.create(directory)
        Map quilt = fixture.target + [id: '26.3-quilt', loader: 'quilt', artifactOf: fixture.target.id]
        fixture.catalog.targets.add(quilt)
        new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
        File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
        catalog.text = JsonOutput.toJson(fixture.catalog)
        fixture.record.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog)
        Map alias = CandidateFiles.parse(new StringReader(JsonOutput.toJson(fixture.record))) as Map
        alias.id = quilt.id
        fixture.manifest.selected_targets.add(quilt.id)
        fixture.manifest.targets.add(alias)
        fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        File metadata = new File(directory, 'publication.json')
        metadata.text = JsonOutput.toJson(Publication.metadata(fixture.file, fixture.root, 'Release notes'))

        File project = new File(fixture.root, 'build-config/publishing')
        project.mkdirs()
        new File(project, 'settings.gradle').text = '''pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
rootProject.name = 'publishing-test'
'''
        new File(project, 'build.gradle').text = new File('../build-config/publishing/build.gradle').text
        def runner = { String target, List<String> tasks ->
            GradleRunner.create().withProjectDir(project).withPluginClasspath().withArguments(tasks + [
                    '-Ptarget=' + target, '-PpublicationMetadata=' + metadata.absolutePath,
                    '-Pcandidate=' + fixture.file.absolutePath, '-PsourceRoot=' + fixture.root.absolutePath,
                    '--stacktrace'])
        }
        assertTrue(runner(fixture.target.id, ['verifyUploadInputs']).build().output
                .contains('Checked publication files for ' + fixture.target.id))
        assertTrue(runner(fixture.target.id, ['planModrinthUpload', '--dry-run']).build().output
                .contains(':planModrinthUpload SKIPPED'))
        assertTrue(runner(quilt.id, ['planModrinthUpload', '--dry-run']).buildAndFail().output
                .contains('Expected one publishing record'))
    }
}
