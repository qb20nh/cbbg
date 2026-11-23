package com.qb20nh.cbbg.parity;

import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Observes pipeline faults and native ownership in the locked candidate and VulkanMod JARs. */
final class ClassicPipelineProbe {
  private static final String FIXTURE = "com/qb20nh/cbbg/parity/ClassicVulkanPipelineFailure";
  private static final String TRACK = "com/qb20nh/cbbg/parity/ClassicVulkanPipelineConstruction";
  private static final String SHADER = "net/vulkanmod/vulkan/shader/SPIRVUtils";
  private static final String GRAPHICS = "net/vulkanmod/vulkan/shader/GraphicsPipeline";
  private static final String PIPELINE = "net/vulkanmod/vulkan/shader/Pipeline";
  private static final String DESCRIPTORS = PIPELINE + "$DescriptorSets";
  private static final String VK = "org/lwjgl/vulkan/VK10";
  private static final String MEMORY = "org/lwjgl/system/MemoryUtil";
  private static final String SHADERC = "org/lwjgl/util/shaderc/Shaderc";
  private static final Set<String> TARGETS =
      Set.of(SHADER, GRAPHICS, PIPELINE, DESCRIPTORS, GRAPHICS + "$VertexInputDescription");
  private static final Set<String> HANDLES =
      Set.of("DescriptorSetLayout", "PipelineLayout", "ShaderModule", "DescriptorPool");

  private ClassicPipelineProbe() {}

  static byte[] attach(String className, boolean candidate, boolean vulkanMod, byte[] bytes) {
    if (!(candidate && className.startsWith("com/qb20nh/cbbg/"))
        && !(vulkanMod && TARGETS.contains(className))) return null;
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    boolean[] changed = {false};
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String type, String signature, String[] exceptions) {
                var delegate = super.visitMethod(access, name, type, signature, exceptions);
                return new Calls(delegate, className, name, type, changed);
              }
            },
            0);
    if (vulkanMod) System.out.println("CBBG_PIPELINE_AGENT class=" + className);
    return changed[0] ? writer.toByteArray() : null;
  }

  private static final class Calls extends MethodVisitor {
    private final String ownerClass;
    private final String method;
    private final String type;
    private final boolean[] changed;

    Calls(
        MethodVisitor delegate, String ownerClass, String method, String type, boolean[] changed) {
      super(Opcodes.ASM9, delegate);
      this.ownerClass = ownerClass;
      this.method = method;
      this.type = type;
      this.changed = changed;
    }

    @Override
    public void visitCode() {
      super.visitCode();
      if (SHADER.equals(ownerClass) && method.equals("compileShader")) {
        super.visitVarInsn(Opcodes.ALOAD, 0);
        super.visitVarInsn(Opcodes.ALOAD, 1);
        hook(FIXTURE, "fragmentSource", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        super.visitVarInsn(Opcodes.ASTORE, 1);
      }
      if (GRAPHICS.equals(ownerClass) && method.equals("cleanUp") && type.equals("()V")) {
        super.visitVarInsn(Opcodes.ALOAD, 0);
        hook(FIXTURE, "observeCleanup", "(Ljava/lang/Object;)V");
      }
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      if (owner.equals(VK) && redirectVulkanCall(name)) return;
      if (owner.equals(SHADERC) && name.equals("shaderc_result_release")) {
        trackHandle("releaseHandle", "ShaderResult");
      }
      if (owner.equals(MEMORY) && name.equals("memFree")) {
        super.visitInsn(Opcodes.DUP);
        hook(TRACK, "releaseBuffer", "(Ljava/lang/Object;)V");
      }
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      if (SHADER.equals(ownerClass)
          && owner.equals(SHADERC)
          && name.equals("shaderc_compile_into_spv")) {
        trackHandle("observeHandle", "ShaderResult");
      }
      if (DESCRIPTORS.equals(ownerClass) && owner.equals(MEMORY)) {
        if (name.equals("memAllocInt")) trackBuffer("DynamicOffsets");
        if (name.equals("memAllocLong")) trackBuffer("DescriptorHandles");
      }
      if (GRAPHICS.equals(ownerClass) && name.equals("calloc") && descriptor.startsWith("(I)")) {
        if (owner.equals("org/lwjgl/vulkan/VkVertexInputBindingDescription")) {
          trackBuffer("VertexBindings");
        } else if (owner.equals("org/lwjgl/vulkan/VkVertexInputAttributeDescription")) {
          trackBuffer("VertexAttributes");
        }
      }
    }

    private boolean redirectVulkanCall(String name) {
      if ((PIPELINE.equals(ownerClass) || DESCRIPTORS.equals(ownerClass))
          && name.startsWith("vkCreate")
          && HANDLES.contains(name.substring(8))) {
        super.visitLdcInsn(name.substring(8));
        hook(
            TRACK,
            "createHandle",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;Ljava/lang/String;)I");
        return true;
      }
      if (DESCRIPTORS.equals(ownerClass) && name.equals("vkAllocateDescriptorSets")) {
        hook(TRACK, "createSets", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;)I");
        return true;
      }
      if (GRAPHICS.equals(ownerClass) && name.equals("vkCreateGraphicsPipelines")) {
        hook(
            FIXTURE,
            "createGraphicsPipelines",
            "(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;)I");
        return true;
      }
      if (name.startsWith("vkDestroy") && HANDLES.contains(name.substring(9))) {
        // Temporarily remove the callbacks argument to observe the device handle beneath it.
        super.visitInsn(Opcodes.DUP_X2);
        super.visitInsn(Opcodes.POP);
        trackHandle("releaseHandle", name.substring(9));
        super.visitInsn(Opcodes.DUP2_X1);
        super.visitInsn(Opcodes.POP2);
      }
      return false;
    }

    private void trackHandle(String event, String kind) {
      super.visitInsn(Opcodes.DUP2);
      super.visitLdcInsn(kind);
      hook(TRACK, event, "(JLjava/lang/String;)V");
    }

    private void trackBuffer(String kind) {
      super.visitInsn(Opcodes.DUP);
      super.visitLdcInsn(kind);
      hook(TRACK, "observeBuffer", "(Ljava/lang/Object;Ljava/lang/String;)V");
    }

    private void hook(String owner, String name, String descriptor) {
      changed[0] = true;
      super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    }
  }
}
