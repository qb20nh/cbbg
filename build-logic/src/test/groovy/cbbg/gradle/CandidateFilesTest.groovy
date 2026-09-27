package cbbg.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.security.MessageDigest
import static org.junit.jupiter.api.Assertions.*

class CandidateFilesTest {
    @TempDir File directory

    @Test void jsonRejectsDuplicateKeysAndTrailingValues() {
        ['{"x":1,"x":2}', '{"nested":{"x":null,"x":3}}', '{} {}'].each { value ->
            assertThrows(Exception) { CandidateFiles.parse(new StringReader(value)) }
        }
        def parsed = CandidateFiles.parse(new StringReader('{"x":25,"y":false,"z":null}'))
        assertEquals(Integer, parsed.x.class)
        assertEquals(false, parsed.y)
        assertNull(parsed.z)
    }

    @Test void canonicalHashKeepsExistingPythonEncoding() {
        String encoded = '{"a":"\\u00e9","z":1}'
        String expected = MessageDigest.getInstance('SHA-256').digest(encoded.getBytes('UTF-8')).encodeHex().toString()
        assertEquals(expected, CandidateFiles.canonicalHash([z: 1, a: 'é']))
    }

    @Test void jsonRejectsInvalidUtf8() {
        File input = new File(directory, 'invalid.json')
        input.bytes = [123, 34, 120, 34, 58, 34, 192, 175, 34, 125] as byte[]
        assertThrows(java.nio.charset.MalformedInputException) { CandidateFiles.read(input) }
    }

    @Test void fileReferencesRejectChangesAndEscapingSymlinks() {
        File file = new File(directory, 'artifact.jar')
        file.text = 'artifact'
        Map reference = CandidateFiles.reference(directory, file.name)
        assertEquals(file, CandidateFiles.checked(directory, reference))
        file.text = 'changed'
        assertThrows(GradleException) { CandidateFiles.checked(directory, reference) }
        ['../outside', '/tmp/file', 'dir\\file'].each { name ->
            assertThrows(GradleException) { CandidateFiles.relativeFile(directory, name) }
        }
        File outside = File.createTempFile('cbbg-candidate', '.jar')
        try {
            java.nio.file.Files.createSymbolicLink(new File(directory, 'link').toPath(), outside.toPath())
            assertThrows(GradleException) { CandidateFiles.relativeFile(directory, 'link') }
        } finally { outside.delete() }
    }

    @Test void releaseIdentityAndExclusiveWrites() {
        CandidateFiles.releaseIdentity('v1.4.0-rc.1', 'a' * 40)
        ['v01.4.0', 'v1.4.0-rc', 'v1.4.0+mc26.3'].each { tag ->
            assertThrows(GradleException) { CandidateFiles.releaseIdentity(tag, 'a' * 40) }
        }
        File output = new File(directory, 'candidate.json')
        CandidateFiles.writeNew(output, [value: 1])
        assertThrows(java.nio.file.FileAlreadyExistsException) { CandidateFiles.writeNew(output, [value: 2]) }
        assertEquals(1, CandidateFiles.read(output).value)
    }
}
