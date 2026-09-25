package cbbg.gradle

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import groovy.json.JsonOutput
import org.gradle.api.GradleException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class CandidateFiles {
    static Object read(File file) {
        Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).withCloseable { Reader input -> parse(input) }
    }

    static Object parse(Reader input) {
        def reader = new JsonReader(input)
        reader.setLenient(false)
        Object value = readValue(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new GradleException('Trailing JSON content')
        value
    }

    private static Object readValue(JsonReader reader) {
        switch (reader.peek()) {
            case JsonToken.BEGIN_OBJECT:
                Map result = [:]
                reader.beginObject()
                while (reader.hasNext()) {
                    String key = reader.nextName()
                    if (result.containsKey(key)) throw new GradleException('Duplicate JSON key: ' + key)
                    result[key] = readValue(reader)
                }
                reader.endObject()
                return result
            case JsonToken.BEGIN_ARRAY:
                List result = []
                reader.beginArray()
                while (reader.hasNext()) result.add(readValue(reader))
                reader.endArray()
                return result
            case JsonToken.STRING: return reader.nextString()
            case JsonToken.BOOLEAN: return reader.nextBoolean()
            case JsonToken.NULL: reader.nextNull(); return null
            case JsonToken.NUMBER:
                String value = reader.nextString()
                if (value ==~ /-?(0|[1-9][0-9]*)/) {
                    BigInteger integer = new BigInteger(value)
                    if (integer.bitLength() < 32) return integer.intValue()
                    if (integer.bitLength() < 64) return integer.longValue()
                    return integer
                }
                return new BigDecimal(value)
            default: throw new GradleException('Invalid JSON value')
        }
    }

    static String sha256(File file) {
        def digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[65536]
            int size
            while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size)
        }
        digest.digest().encodeHex().toString()
    }

    static Object sorted(Object value) {
        if (value instanceof Map) return new TreeMap(value.collectEntries { key, child -> [(key): sorted(child)] })
        if (value instanceof List) return value.collect { sorted(it) }
        value
    }

    static String canonicalHash(Object value) {
        MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(sorted(value)).getBytes('UTF-8'))
                .encodeHex().toString()
    }

    static File relativeFile(File base, Object path) {
        if (!(path instanceof String) || !path || new File(path).absolute || path.contains('\\')) {
            throw new GradleException('Candidate paths must be relative')
        }
        File root = base.canonicalFile
        File file = new File(root, path).canonicalFile
        if (file == root || !file.toPath().startsWith(root.toPath()) || !file.isFile()) {
            throw new GradleException('Missing or escaping candidate file: ' + path)
        }
        file
    }

    static File checked(File base, Map reference) {
        File file = relativeFile(base, reference.path)
        if (!(reference.sha256 instanceof String) || !(reference.sha256 ==~ /[0-9a-f]{64}/) ||
                sha256(file) != reference.sha256) {
            throw new GradleException('Changed or invalid candidate file: ' + reference.path)
        }
        file
    }

    static Map reference(File base, String path) {
        File file = relativeFile(base, path)
        [path: base.canonicalFile.toPath().relativize(file.toPath()).toString().replace('\\', '/'), sha256: sha256(file)]
    }

    static void releaseIdentity(String tag, String commit) {
        String number = '(?:0|[1-9][0-9]*)'
        String suffix = '(?:-[A-Za-z][0-9A-Za-z-]*(?:\\.[A-Za-z][0-9A-Za-z-]*)*\\.[1-9][0-9]*)?'
        if (!(tag ==~ ('v' + number + '\\.' + number + '\\.' + number + suffix))) {
            throw new GradleException('Invalid release tag')
        }
        if (!(commit ==~ /[0-9a-f]{40}/)) throw new GradleException('Invalid source commit')
    }

    static void writeNew(File file, Object value) {
        Files.writeString(file.toPath(), JsonOutput.prettyPrint(JsonOutput.toJson(value)) + '\n',
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
