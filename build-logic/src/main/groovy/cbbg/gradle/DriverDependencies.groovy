package cbbg.gradle

import org.gradle.api.GradleException
import proguard.classfile.ClassPool
import proguard.classfile.ProgramClass
import proguard.classfile.io.ProgramClassReader
import proguard.classfile.util.ClassReferenceInitializer
import proguard.classfile.util.StringReferenceInitializer
import proguard.classfile.visitor.ClassCollector
import proguard.classfile.visitor.ReferencedClassVisitor
import proguard.classfile.constant.visitor.AllConstantVisitor
import proguard.classfile.constant.RefConstant
import proguard.classfile.constant.ClassConstant

import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipException

/** Contents needed by the entrypoints of one packaged test suite. */
class DriverDependencies {
    static Map inventory(File driver) {
        Map<String, byte[]> entries = new TreeMap<>()
        ZipFile archive
        try { archive = new ZipFile(driver) }
        catch (ZipException ignored) {
            // Archives without a readable class graph require a matching full checksum.
            return [schema: 1, driver_sha256: CandidateFiles.sha256(driver), complete: false, files: [:]]
        }
        archive.withCloseable { jar ->
            jar.entries().each { entry ->
                if (!entry.directory) {
                    if (entries.containsKey(entry.name)) throw new GradleException('Duplicate test driver entry: ' + entry.name)
                    entries[entry.name] = jar.getInputStream(entry).withCloseable { it.bytes }
                }
            }
        }
        Map metadata = CandidateFiles.parse(new StringReader(new String(entries['fabric.mod.json'], 'UTF-8'))) as Map
        boolean fullArchive = metadata.languageAdapters || metadata.entrypoints.values().flatten().any {
            it instanceof Map && it.adapter != null && it.adapter != 'default'
        }
        ClassPool pool = new ClassPool()
        entries.findAll { name, bytes -> name.endsWith('.class') }.each { name, bytes ->
            ProgramClass type = new ProgramClass()
            type.accept(new ProgramClassReader(new DataInputStream(new ByteArrayInputStream(bytes))))
            pool.addClass(type)
        }
        pool.classesAccept(new ClassReferenceInitializer(pool, new ClassPool()))
        pool.classesAccept(new AllConstantVisitor(new StringReferenceInitializer(pool, new ClassPool())))
        Set<String> packages = pool.classes().collect { type ->
            String name = type.name
            name.contains('/') ? name.substring(0, name.lastIndexOf('/')) : ''
        } as Set<String>
        Set<String> pending = new LinkedHashSet<>()
        metadata.entrypoints.values().flatten().each { entry ->
            String name = entry instanceof Map ? entry.value : entry
            pending.add(name.replace('.', '/').split('::')[0])
        }
        (metadata.mixins ?: []).each { entry ->
            String name = entry instanceof Map ? entry.config : entry
            Map config = CandidateFiles.parse(new StringReader(new String(entries[name], 'UTF-8'))) as Map
            ['mixins', 'client', 'server'].each { side ->
                (config[side] ?: []).each { pending.add((config.package + '.' + it).replace('.', '/')) }
            }
            if (config.plugin) pending.add(config.plugin.replace('.', '/'))
        }
        Set<String> visited = new LinkedHashSet<>()
        while (pending) {
            String name = pending.iterator().next()
            pending.remove(name)
            if (!visited.add(name)) continue
            def type = pool.getClass(name)
            if (type == null) throw new GradleException('Missing test entrypoint or dependency: ' + name)
            boolean reflectiveLoad = type.constantPool.any { constant ->
                constant instanceof RefConstant &&
                        ((constant.getClassName(type) == 'java/lang/Class' &&
                                (constant.getName(type) == 'forName' || constant.getName(type).startsWith('getDeclared'))) ||
                         constant.getClassName(type).startsWith('java/lang/reflect/') ||
                         (constant.getName(type) in ['loadClass', 'findClass', 'defineClass']))
            }
            // Reflection can load helpers that have no static class reference.
            if (reflectiveLoad) fullArchive = true
            boolean missingHelper = type.constantPool.any { constant ->
                if (!(constant instanceof ClassConstant)) return false
                String reference = constant.getName(type).replaceFirst(/^\[+L/, '').replaceFirst(/;$/, '')
                String owner = reference.contains('/') ? reference.substring(0, reference.lastIndexOf('/')) : ''
                packages.contains(owner) && pool.getClass(reference) == null
            }
            if (missingHelper) fullArchive = true
            Set references = new LinkedHashSet()
            type.accept(new ReferencedClassVisitor(new ClassCollector(references)))
            references.each { if (!visited.contains(it.name)) pending.add(it.name) }
            // Inner classes may also be reached through reflection.
            entries.keySet().findAll { it.startsWith(name + '$') && it.endsWith('.class') }.each {
                String inner = it.substring(0, it.length() - 6)
                if (!visited.contains(inner)) pending.add(inner)
            }
        }
        Map files = entries.findAll { name, bytes -> fullArchive || !name.endsWith('.class') || visited.contains(name.substring(0, name.length() - 6)) }
                .collectEntries { name, bytes -> [(name): MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()] }
        [schema: 1, driver_sha256: CandidateFiles.sha256(driver), complete: true, full_archive: fullArchive, files: files]
    }
}
