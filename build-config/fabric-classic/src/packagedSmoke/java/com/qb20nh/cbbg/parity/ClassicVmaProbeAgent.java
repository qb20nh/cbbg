package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.gametest.ReleaseMapping;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Routes selected allocations and frees from the two locked JARs through the test fixture. */
public final class ClassicVmaProbeAgent {
  private static final String FIXTURE = "com/qb20nh/cbbg/parity/ClassicVulkanAllocation";
  private static final String NATIVE_FIXTURE =
      "com/qb20nh/cbbg/parity/ClassicVulkanNativeAllocation";
  private static final String MEMORY_MANAGER = "net/vulkanmod/vulkan/memory/MemoryManager";
  private static final Set<String> NATIVE_TARGETS =
      Set.of(
          "net/vulkanmod/vulkan/texture/VulkanImage",
          "net/vulkanmod/vulkan/framebuffer/Framebuffer",
          "net/vulkanmod/vulkan/framebuffer/RenderPass");
  private static final Set<String> NATIVE_RESOURCES =
      Set.of("ImageView", "RenderPass", "Framebuffer");
  private static final String ALLOCATE =
      "(JLorg/lwjgl/vulkan/VkImageCreateInfo;Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;"
          + "Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;Lorg/lwjgl/util/vma/VmaAllocationInfo;)I";

  private ClassicVmaProbeAgent() {}

  public static void premain(String modPath, Instrumentation instrumentation)
      throws MalformedURLException {
    URL mod = Path.of(modPath).toUri().toURL();
    boolean samplerProbe = System.getProperty("cbbg.test.vulkanSamplerFault") != null;
    boolean pipelineProbe = System.getProperty("cbbg.test.vulkanPipelineFault") != null;
    URL candidate = Path.of(System.getProperty("cbbg.test.jar")).toUri().toURL();
    boolean nativeProbe = System.getProperty("cbbg.test.vulkanNativeFailure") != null;
    String scene =
        nativeProbe
            ? ReleaseMapping.className("com.qb20nh.cbbg.render.vulkanmod.VulkanModMainPass$Scene")
                .replace('.', '/')
            : "";
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (name == null || domain == null || domain.getCodeSource() == null) return null;
            URL source = domain.getCodeSource().getLocation();
            if (pipelineProbe) {
              return ClassicPipelineProbe.attach(
                  name, candidate.equals(source), mod.equals(source), bytes);
            }
            if (samplerProbe) {
              return mod.equals(source) ? ClassicSamplerProbe.attach(name, bytes) : null;
            }
            if (MEMORY_MANAGER.equals(name) && mod.equals(source)) {
              return attachProbe(bytes, name, true);
            }
            if (nativeProbe
                && ((NATIVE_TARGETS.contains(name) && mod.equals(source))
                    || (scene.equals(name) && candidate.equals(source)))) {
              return attachProbe(bytes, name, false);
            }
            return null;
          }
        });
  }

  private static byte[] attachProbe(byte[] bytes, String ownerClass, boolean vma) {
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    int[] sites = new int[2];
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9, writer) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                return new MethodVisitor(
                    Opcodes.ASM9,
                    super.visitMethod(access, name, descriptor, signature, exceptions)) {
                  @Override
                  public void visitCode() {
                    super.visitCode();
                    if (vma
                        && name.equals("freeImage")
                        && descriptor.equals("(JJ)V")
                        && (access & Opcodes.ACC_STATIC) != 0) {
                      super.visitVarInsn(Opcodes.LLOAD, 0);
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC, FIXTURE, "observeFree", "(J)V", false);
                      sites[1]++;
                    }
                  }

                  @Override
                  public void visitMethodInsn(
                      int opcode,
                      String owner,
                      String name,
                      String descriptor,
                      boolean isInterface) {
                    if (vma
                        && opcode == Opcodes.INVOKESTATIC
                        && owner.equals("org/lwjgl/util/vma/Vma")
                        && name.equals("vmaCreateImage")
                        && descriptor.equals(ALLOCATE)) {
                      super.visitMethodInsn(
                          Opcodes.INVOKESTATIC,
                          FIXTURE,
                          "createImage",
                          "(JLjava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;"
                              + "Lorg/lwjgl/PointerBuffer;Ljava/lang/Object;)I",
                          false);
                      sites[0]++;
                    } else if (!vma
                        && opcode == Opcodes.INVOKESTATIC
                        && owner.equals("org/lwjgl/vulkan/VK10")
                        && redirectNative(mv, name, descriptor)) {
                      sites[0]++;
                    } else {
                      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                  }
                };
              }
            },
            0);
    if (vma) {
      System.out.println("CBBG_ALLOC_AGENT allocationSites=" + sites[0] + " freeSites=" + sites[1]);
      return sites[0] == 1 && sites[1] == 1 ? writer.toByteArray() : null;
    }
    System.out.println("CBBG_NATIVE_AGENT class=" + ownerClass + " calls=" + sites[0]);
    return sites[0] > 0 ? writer.toByteArray() : null;
  }

  private static boolean redirectNative(MethodVisitor visitor, String name, String descriptor) {
    boolean create = name.startsWith("vkCreate");
    String prefix = create ? "vkCreate" : "vkDestroy";
    if (!name.startsWith(prefix)) return false;
    String resource = name.substring(prefix.length());
    if (!NATIVE_RESOURCES.contains(resource)) return false;
    String device = "Lorg/lwjgl/vulkan/VkDevice;";
    String allocator = "Lorg/lwjgl/vulkan/VkAllocationCallbacks;";
    String expected =
        create
            ? "("
                + device
                + "Lorg/lwjgl/vulkan/Vk"
                + resource
                + "CreateInfo;"
                + allocator
                + "Ljava/nio/LongBuffer;)I"
            : "(" + device + "J" + allocator + ")V";
    if (!descriptor.equals(expected)) return false;
    String method = (create ? "create" : "destroy") + resource;
    String shape =
        create
            ? "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/nio/LongBuffer;)I"
            : "(Ljava/lang/Object;JLjava/lang/Object;)V";
    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, NATIVE_FIXTURE, method, shape, false);
    return true;
  }
}
