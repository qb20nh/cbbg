package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.api.GradleException

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

class FabricBrandingTest {
    @TempDir File root

    @Test
    void sharesPublicMetadataWhileKeepingTargetRuntimeDeclarations() {
        File source = new File(System.getProperty('cbbg.repository'), 'src/main/resources/fabric.mod.json')
        Map shared = new JsonSlurper().parse(source) as Map
        File output = new File(root, 'fabric.mod.json')
        Map runtime = [id: 'cbbg', version: '1.4.1+mc26.3-fabric', environment: 'client',
                depends: [minecraft: '26.3', java: '>=25'],
                entrypoints: [preLaunch: ['example.EarlyInit'], client: ['example.Client']],
                mixins: ['cbbg.renderpearl.mixins.json'], languageAdapters: [early: 'example.Adapter']]
        output.setText(JsonOutput.toJson(runtime), 'UTF-8')

        FabricBranding.apply(source, output)

        Map result = new JsonSlurper().parse(output) as Map
        runtime.each { key, value -> assertEquals(value, result[key], key) }
        ['name', 'description', 'authors', 'contributors', 'contact', 'license', 'icon'].each { field ->
            assertNotNull(shared[field], field)
            assertEquals(shared[field], result[field], field)
        }
        assertTrue(new File(source.parentFile, result.icon as String).isFile())
    }

    @Test
    void readsUpdatedSharedMetadata() {
        File shared = new File(root, 'shared.json')
        File output = new File(root, 'target.json')
        shared.setText('{"name":"First"}', 'UTF-8')
        output.setText('{"version":"1.4.1"}', 'UTF-8')
        FabricBranding.apply(shared, output)
        shared.setText('{"name":"Updated"}', 'UTF-8')
        FabricBranding.apply(shared, output)
        assertEquals('Updated', new JsonSlurper().parse(output).name)
        assertEquals('1.4.1', new JsonSlurper().parse(output).version)
    }

    @Test
    void packageVerificationRejectsMissingIconAndDescription() {
        File source = new File(System.getProperty('cbbg.repository'), 'src/main/resources/fabric.mod.json')
        Map metadata = new JsonSlurper().parse(source) as Map
        File artifact = new File(root, 'mod.jar')
        def write = { boolean includeIcon ->
            new ZipOutputStream(artifact.newOutputStream()).withCloseable { zip ->
                zip.putNextEntry(new ZipEntry('fabric.mod.json'))
                zip.write(JsonOutput.toJson(metadata).getBytes('UTF-8'))
                zip.closeEntry()
                if (includeIcon) {
                    zip.putNextEntry(new ZipEntry(metadata.icon as String))
                    zip.write(new File(source.parentFile, metadata.icon as String).bytes)
                    zip.closeEntry()
                }
            }
        }
        write(true)
        FabricBranding.verify(source, artifact)
        write(false)
        assertThrows(GradleException) { FabricBranding.verify(source, artifact) }
        metadata.remove('description')
        write(true)
        assertThrows(GradleException) { FabricBranding.verify(source, artifact) }
    }
}
