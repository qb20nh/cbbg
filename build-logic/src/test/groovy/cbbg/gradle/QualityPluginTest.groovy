package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class QualityPluginTest {
    @TempDir File directory

    @BeforeEach void configure() {
        write('settings.gradle', "rootProject.name = 'quality-fixture'\n")
        write('build.gradle', '''
plugins { id 'java-library'; id 'cbbg.quality'; id 'cbbg.formatting' }
repositories { mavenCentral() }
tasks.withType(JavaCompile).configureEach { options.release = 8 }
sourceSets.create('gametest')
sourceSets.create('processedGametest')
tasks.register('ciCheck')
''')
    }

    @Test void formattingAndAllSourceSetsAreCheckedWithoutChangingTheBytecodeTarget() {
        for (String sourceSet : ['main', 'test', 'gametest', 'processedGametest']) {
            write("src/${sourceSet}/java/example/${sourceSet.capitalize()}.java",
                    "package example; @org.jspecify.annotations.NullMarked public final class ${sourceSet.capitalize()} {}")
        }
        assertTrue(runner('spotlessCheck').buildAndFail().output.contains('spotlessJavaCheck'))
        runner('spotlessApply').build()
        // These fixture classes exercise compilation, rather than containing JUnit tests.
        def result = runner('ciCheck').build()
        assertNotNull(result.task(':qualityCheck'))
        assertFalse(result.output.contains('No rules found in ruleset'), result.output)
        for (String sourceSet : ['Main', 'Test', 'Gametest', 'ProcessedGametest']) {
            assertNotNull(result.task(':pmd' + sourceSet))
            assertNotNull(result.task(':cpd' + sourceSet))
        }
        byte[] classes = new File(directory, 'build/classes/java/main/example/Main.class').bytes
        assertEquals(52, ((classes[6] & 0xff) << 8) | (classes[7] & 0xff))
        assertNotNull(runner('check', '-x', 'test').build().task(':qualityCheck'))
        assertNotNull(runner('spotlessCheck').build().task(':spotlessJavaCheck'))
    }

    @Test void targetToolchainsDoNotReplaceTheAnalysisCompiler() {
        new File(directory, 'build.gradle').append('''
java.toolchain.languageVersion = JavaLanguageVersion.of(17)
tasks.withType(JavaCompile).configureEach { options.release = 17 }
tasks.named('compileJava') {
    doLast { assert javaCompiler.get().metadata.languageVersion.asInt() == 25 }
}
''')
        writeClass('Compatible', 'public int value() { return 17; }')
        runner('compileJava').build()
        byte[] classes = new File(directory, 'build/classes/java/main/example/Compatible.class').bytes
        assertEquals(61, ((classes[6] & 0xff) << 8) | (classes[7] & 0xff))
    }

    @Test void errorProneRejectsSelfComparison() {
        writeClass('Broken', 'public boolean same(int value) { return value == value; }')
        def result = runner('compileJava').buildAndFail()
        assertTrue(result.output.contains('[IdentityBinaryExpression]'), result.output)
    }

    @Test void nullAwayRejectsNullableValuesAndNullableGenericElements() {
        writeClass('Broken', '''
public int length(@org.jspecify.annotations.Nullable String value) { return value.length(); }
''')
        assertTrue(runner('compileJava').buildAndFail().output.contains('[NullAway]'))
        writeClass('Broken', '''
public int length(java.util.List<@org.jspecify.annotations.Nullable String> values) {
    return values.get(0).length();
}
''')
        assertTrue(runner('compileJava').buildAndFail().output.contains('[NullAway]'))
    }

    @Test void unmarkedClassesCannotAvoidNullChecking() {
        write('src/main/java/example/Broken.java', 'package example; public final class Broken {}')
        assertTrue(runner('compileJava').buildAndFail().output.contains('[RequireExplicitNullMarking]'))
    }

    @Test void newerBytecodeTargetsTreatWarningsAsErrors() {
        new File(directory, 'build.gradle').append('\ntasks.withType(JavaCompile).configureEach { options.release = 25 }\n')
        writeClass('Broken', 'public String upper(String value) { return value.toUpperCase(); }')
        def result = runner('compileJava').buildAndFail()
        assertTrue(result.output.contains('[StringCaseLocaleUsage]'))
        assertTrue(result.output.contains('-Werror'))
    }

    @Test void identitySuppressionIsLocalToTheReviewedComparison() {
        new File(directory, 'build.gradle').append('\ntasks.withType(JavaCompile).configureEach { options.release = 25 }\n')
        String comparison = '''
public boolean same(java.util.List<String> first, java.util.List<String> second) {
    return first == second;
}
'''
        writeClass('Identity', '@SuppressWarnings("ReferenceEquality")\n' + comparison)
        runner('compileJava').build()
        writeClass('Identity', comparison)
        assertTrue(runner('compileJava').buildAndFail().output.contains('[ReferenceEquality]'))
    }

    @Test void mixinCallbacksKeepTheirSignaturesAndNullChecks() {
        new File(directory, 'build.gradle').append('\ntasks.withType(JavaCompile).configureEach { options.release = 25 }\n')
        write('src/main/java/org/spongepowered/asm/mixin/injection/Inject.java', '''
package org.spongepowered.asm.mixin.injection;
@org.jspecify.annotations.NullMarked public @interface Inject {}
''')
        write('src/main/java/org/spongepowered/asm/mixin/Shadow.java', '''
package org.spongepowered.asm.mixin;
@org.jspecify.annotations.NullMarked public @interface Shadow {}
''')
        writeClass('Callback', '''
@org.spongepowered.asm.mixin.injection.Inject
private void callback(int width, int height) {}
@org.spongepowered.asm.mixin.Shadow
private static void shadow(int width, int height) { throw new AssertionError(); }
''')
        runner('compileJava').build()
        writeClass('Callback', '''
@org.spongepowered.asm.mixin.injection.Inject
private int callback(@org.jspecify.annotations.Nullable String value, int width) {
    return value.length();
}
''')
        assertTrue(runner('compileJava').buildAndFail().output.contains('[NullAway]'))
    }

    @Test void clearingTheMinecraftPipelineCacheUsesItsRuntimeNullContract() {
        write('src/main/java/com/mojang/blaze3d/pipeline/PipelineCache.java', '''
package com.mojang.blaze3d.pipeline;
@org.jspecify.annotations.NullMarked public final class PipelineCache {}
''')
        write('src/main/java/com/mojang/blaze3d/systems/RenderSystem.java', '''
package com.mojang.blaze3d.systems;
import com.mojang.blaze3d.pipeline.PipelineCache;
@org.jspecify.annotations.NullMarked public final class RenderSystem {
    private static @org.jspecify.annotations.Nullable PipelineCache current;
    public static @org.jspecify.annotations.Nullable PipelineCache setCurrentPipelineCache(PipelineCache cache) {
        PipelineCache previous = current;
        current = cache;
        return previous;
    }
}
''')
        writeClass('Cache', '''
public void clear() { com.mojang.blaze3d.systems.RenderSystem.setCurrentPipelineCache(null); }
''')
        runner('compileJava').build()
        writeClass('Cache', '''
public int length(String value) { return value.length(); }
public int broken() { return length(null); }
''')
        assertTrue(runner('compileJava').buildAndFail().output.contains('[NullAway]'))
    }

    @Test void pmdRejectsAnInvalidNullCondition() {
        writeClass('Broken', '''
public boolean nonempty(@org.jspecify.annotations.Nullable String value) {
    return value != null || value.length() > 0;
}
''')
        // Exercise PMD independently of the compiler's check of the same bad condition.
        def result = runner('pmdMain', '-x', 'compileJava').buildAndFail()
        assertTrue(result.output.contains('PMD rule violations'))
        assertTrue(new File(directory, 'build/reports/pmd/main.xml').text.contains('BrokenNullCheck'))
    }

    @Test void cpdRejectsDuplicateBlocksAndLexicalErrors() {
        String body = '''
public int sum(int[] values) {
    int result = 0;
    for (int value : values) {
        if (value > 10) { result += value * 3; }
        else if (value > 5) { result += value * 2; }
        else if (value > 0) { result += value; }
        else if (value < -10) { result -= value * 3; }
        else if (value < -5) { result -= value * 2; }
        else { result -= value; }
    }
    return result;
}
'''
        writeClass('First', body)
        writeClass('Second', body)
        assertTrue(runner('cpdMain').buildAndFail().output.contains('non-zero exit value 4'))
        new File(directory, 'src/main/java/example/Second.java').delete()
        writeClass('First', 'public String invalid() { return "unterminated; }')
        assertTrue(runner('cpdMain').buildAndFail().output.contains('non-zero exit value 5'))
    }

    private void writeClass(String name, String body) {
        write("src/main/java/example/${name}.java",
                "package example;\n@org.jspecify.annotations.NullMarked public final class ${name} {\n${body}\n}\n")
    }

    private GradleRunner runner(String... tasks) {
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments(tasks.toList() + ['--stacktrace', '--console=plain'])
    }

    private void write(String path, String text) {
        File file = new File(directory, path)
        file.parentFile.mkdirs()
        file.setText(text, 'UTF-8')
    }
}
