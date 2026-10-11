package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.util.zip.ZipFile

class FabricBranding {
    private static final List<String> FIELDS =
            ['name', 'description', 'authors', 'contributors', 'contact', 'license', 'icon']

    static void apply(File sharedDescriptor, File targetDescriptor) {
        def parser = new JsonSlurper()
        Map shared = parser.parse(sharedDescriptor) as Map
        Map target = parser.parse(targetDescriptor) as Map
        for (String field : FIELDS) {
            target[field] = shared[field]
        }
        ReproducibleText.writeJson(targetDescriptor, target)
    }

    static void verify(File sharedDescriptor, File artifact) {
        Map shared = new JsonSlurper().parse(sharedDescriptor) as Map
        new ZipFile(artifact).withCloseable { zip ->
            Map target = new JsonSlurper().parse(zip.getInputStream(zip.getEntry('fabric.mod.json'))) as Map
            for (String field : FIELDS) {
                if (target[field] != shared[field]) {
                    throw new GradleException('Packaged Fabric metadata differs: ' + field)
                }
            }
            def icon = zip.getEntry(shared.icon as String)
            if (icon == null || zip.getInputStream(icon).bytes !=
                    new File(sharedDescriptor.parentFile, shared.icon as String).bytes) {
                throw new GradleException('Missing or incorrect mod icon')
            }
        }
    }
}
