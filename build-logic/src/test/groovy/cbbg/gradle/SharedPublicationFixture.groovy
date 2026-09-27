package cbbg.gradle

import groovy.json.JsonOutput
import java.util.zip.ZipFile

class SharedPublicationFixture {
    static Map multiple(File directory) {
        Map fixture = CandidateFixture.create(directory)
        Map second = CandidateFixture.create(new File(directory, 'second'))
        Map target = second.target + [id: '26.2-fabric', minecraft: '26.2']
        Map record = CandidateFiles.parse(new StringReader(JsonOutput.toJson(second.record))) as Map
        record.id = target.id
        Map names = second.bundle.listFiles().collectEntries { [(it.name): 'second-' + it.name.replace('26.3', '26.2')] }
        CandidateManifest.references(record).each { reference ->
            String previous = reference.path
            File original = new File(second.bundle, previous)
            File copied = new File(fixture.bundle, names[previous])
            if (previous == second.record.artifact.path) {
                Map entries = [:]
                new ZipFile(original).withCloseable { zip ->
                    zip.entries().each { entry ->
                        byte[] bytes = zip.getInputStream(entry).bytes
                        entries[entry.name] = entry.name == 'fabric.mod.json'
                                ? new String(bytes, 'UTF-8').replace('26.3', '26.2').getBytes('UTF-8') : bytes
                    }
                }
                CandidateFixture.archive(copied, entries)
            } else if (previous.endsWith('.json')) {
                String text = original.getText('UTF-8').replace('26.3', '26.2')
                names.each { oldName, newName -> text = text.replace('"' + oldName + '"', '"' + newName + '"') }
                copied.setText(text, 'UTF-8')
            } else copied.bytes = original.bytes
            reference.path = copied.name
            reference.sha256 = CandidateFiles.sha256(copied)
        }
        fixture.catalog.targets.add(target)
        new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
        File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
        catalog.text = JsonOutput.toJson(fixture.catalog)
        record.client_tests.catalog = fixture.record.client_tests.catalog
        fixture.record.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog)
        // The second catalog copy is superseded by the single verified catalog.
        new File(fixture.bundle, names[second.record.client_tests.catalog.path]).delete()
        fixture.manifest.selected_targets.add(target.id)
        fixture.manifest.targets.add(record)
        fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        CandidateFixture.checksums(fixture.bundle)
        fixture
    }
}
