package mchorse.bbs_mod.mixin.client.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mchorse.bbs_mod.utils.iris.ShaderCurves;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Function;

/* Publishes the name of the program Iris is currently reading so that
 * {@link ShaderCurves#processSource} only rewrites source destined for Iris'
 * own rendering pipeline.
 *
 * Every shaderpack program read funnels through this 7-arg readProgramSource:
 * the 6-arg overload delegates to it, readProgramArray calls the 6-arg, and the
 * programs with an explicit blend-mode call it directly. Wrapping this single
 * method therefore tags all of Iris' (and colorwheel's) program reads.
 *
 * This replaces the previous per-mod skip mixins: third-party consumers that
 * read the pack for their own pipelines are handled automatically - either they
 * bypass readProgramSource (voxy's makePatch calls the source provider directly,
 * so CURRENT_PROGRAM stays null) or they use their own program names (colorwheel's
 * clrwl_* programs, which aren't recognised as Iris programs). Both cases leave
 * the source untouched. */
@Mixin(value = ProgramSet.class, remap = false)
public class IrisProgramSourceMixin
{
    @WrapMethod(method = "readProgramSource(Lnet/irisshaders/iris/shaderpack/include/AbsolutePackPath;Ljava/util/function/Function;Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/properties/ShaderProperties;Lnet/irisshaders/iris/gl/blending/BlendModeOverride;Z)Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;")
    private static ProgramSource bbs$tagCurrentProgram(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider, String program, ProgramSet programSet, ShaderProperties properties, BlendModeOverride blendModeOverride, boolean readTesselation, Operation<ProgramSource> original)
    {
        String previous = ShaderCurves.CURRENT_PROGRAM.get();

        ShaderCurves.CURRENT_PROGRAM.set(program);

        try
        {
            return original.call(directory, sourceProvider, program, programSet, properties, blendModeOverride, readTesselation);
        }
        finally
        {
            ShaderCurves.CURRENT_PROGRAM.set(previous);
        }
    }
}
