package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class RetraceLogTest {
    @TempDir File directory

    @Test void decodesWithTheReleaseMappingAndRejectsChangedMaps() {
        def fixture = CandidateFixture.create(directory)
        File mapping = new File(fixture.bundle, fixture.record.mapping.path)
        mapping.text = 'example.Renderer -> a:\n    1:1:void render():42:42 -> b\n'
        fixture.record.mapping.sha256 = CandidateFiles.sha256(mapping)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        File crash = new File(directory, 'crash.txt')
        crash.text = 'java.lang.IllegalStateException: example\n\tat a.b(Renderer.java:1)\n'
        File output = new File(directory, 'decoded.txt')
        RetraceLog.translate(fixture.file, '26.3-fabric', crash, output)
        assertTrue(output.text.contains('example.Renderer.render(Renderer.java:42)'), output.text)
        assertThrows(Exception) { RetraceLog.translate(fixture.file, '26.3-fabric', crash, output) }
        assertThrows(Exception) { RetraceLog.translate(fixture.file, '26.2-fabric', crash, new File(directory, 'wrong.txt')) }
        mapping.append('changed')
        assertThrows(Exception) { RetraceLog.translate(fixture.file, '26.3-fabric', crash, new File(directory, 'changed.txt')) }
    }
}
