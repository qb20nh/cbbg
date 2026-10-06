package cbbg.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class GameTestMappingsTest {
    @Test
    void selectsRuntimeNamesFromThreeNamespaces() {
        def names = GameTestMappings.names([
                'tiny\t2\t0\tofficial\tintermediary\tnamed',
                'c\ta\tnet/minecraft/class_1\tnet/minecraft/Example',
                '\tf\tI\tb\tfield_2\tvalue',
                '\tm\t()Ljava/lang/String;\tc\tmethod_3\tcontent',
                '\tm\t(I)V\td\tmethod_4\twrite'
        ])
        assertEquals(['field|net.minecraft.class_1|value': 'field_2',
                      'method|net.minecraft.class_1|content': 'method_3'], names)
    }

    @Test
    void requiresBothNamespaces() {
        assertThrows(GradleException) { GameTestMappings.names(['tiny\t2\t0\tofficial\tnamed']) }
    }
}
