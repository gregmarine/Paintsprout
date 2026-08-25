package com.symmetricalpalmtree.paintsprout.data.soil

import com.symmetricalpalmtree.paintsprout.data.soil.codec.Params
import com.symmetricalpalmtree.paintsprout.paint.CanvasParams
import com.symmetricalpalmtree.paintsprout.paint.ChalkboardParams
import com.symmetricalpalmtree.paintsprout.paint.ConcreteParams
import com.symmetricalpalmtree.paintsprout.paint.MetalParams
import com.symmetricalpalmtree.paintsprout.paint.StoneParams
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.WatercolorParams
import com.symmetricalpalmtree.paintsprout.paint.WoodParams

/**
 * The seven surface parameter structs, into and out of one `params` bag.
 *
 * **All seven are stored, not just the one in use.** That mirrors what a surface
 * change actually records: the user's canvas tuning is still their canvas tuning
 * while they are working on wood, and switching back has to find it. It is also
 * why this can be a flat bag rather than a discriminated union — the keys are
 * prefixed by surface, nothing collides, and a surface the file has never carried
 * simply reads back as defaults.
 *
 * Reading is total: a missing key is that field's default, so a bag written by an
 * older build renders, and one written by a newer build renders too, minus
 * whatever it knows that this build does not.
 */
object SurfaceParamsCodec {

    fun encode(
        canvas: CanvasParams = CanvasParams(),
        watercolor: WatercolorParams = WatercolorParams(),
        wood: WoodParams = WoodParams(),
        stone: StoneParams = StoneParams(),
        concrete: ConcreteParams = ConcreteParams(),
        metal: MetalParams = MetalParams(),
        chalkboard: ChalkboardParams = ChalkboardParams(),
    ): Params = Params.of(
        mapOf(
            "canvas.tint" to canvas.tint,
            "canvas.weave" to canvas.weave,
            "canvas.grain" to canvas.grain,

            "watercolor.tint" to watercolor.tint,
            "watercolor.texture" to watercolor.texture,
            "watercolor.mottle" to watercolor.mottle,
            "watercolor.grain" to watercolor.grain,

            "wood.tint" to wood.tint,
            "wood.grain" to wood.grain,
            "wood.scale" to wood.scale,
            "wood.weathering" to wood.weathering,

            "stone.tint" to stone.tint,
            "stone.mottle" to stone.mottle,
            "stone.cracks" to stone.cracks,
            "stone.crackContrast" to stone.crackContrast,
            "stone.grain" to stone.grain,

            "concrete.tint" to concrete.tint,
            "concrete.staining" to concrete.staining,
            "concrete.pores" to concrete.pores,
            "concrete.grit" to concrete.grit,

            "metal.tint" to metal.tint,
            "metal.grain" to metal.grain,
            "metal.sheen" to metal.sheen,
            "metal.scratches" to metal.scratches,

            "chalkboard.tint" to chalkboard.tint,
            "chalkboard.ghosting" to chalkboard.ghosting,
            "chalkboard.dust" to chalkboard.dust,
        ),
    )

    fun canvas(p: Params): CanvasParams = CanvasParams().let { d ->
        CanvasParams(
            tint = p.color("canvas.tint", d.tint),
            weave = p.float("canvas.weave", d.weave),
            grain = p.float("canvas.grain", d.grain),
        )
    }

    fun watercolor(p: Params): WatercolorParams = WatercolorParams().let { d ->
        WatercolorParams(
            tint = p.color("watercolor.tint", d.tint),
            texture = p.float("watercolor.texture", d.texture),
            mottle = p.float("watercolor.mottle", d.mottle),
            grain = p.float("watercolor.grain", d.grain),
        )
    }

    fun wood(p: Params): WoodParams = WoodParams().let { d ->
        WoodParams(
            tint = p.color("wood.tint", d.tint),
            grain = p.float("wood.grain", d.grain),
            scale = p.float("wood.scale", d.scale),
            weathering = p.float("wood.weathering", d.weathering),
        )
    }

    fun stone(p: Params): StoneParams = StoneParams().let { d ->
        StoneParams(
            tint = p.color("stone.tint", d.tint),
            mottle = p.float("stone.mottle", d.mottle),
            cracks = p.float("stone.cracks", d.cracks),
            crackContrast = p.float("stone.crackContrast", d.crackContrast),
            grain = p.float("stone.grain", d.grain),
        )
    }

    fun concrete(p: Params): ConcreteParams = ConcreteParams().let { d ->
        ConcreteParams(
            tint = p.color("concrete.tint", d.tint),
            staining = p.float("concrete.staining", d.staining),
            pores = p.float("concrete.pores", d.pores),
            grit = p.float("concrete.grit", d.grit),
        )
    }

    fun metal(p: Params): MetalParams = MetalParams().let { d ->
        MetalParams(
            tint = p.color("metal.tint", d.tint),
            grain = p.float("metal.grain", d.grain),
            sheen = p.float("metal.sheen", d.sheen),
            scratches = p.float("metal.scratches", d.scratches),
        )
    }

    fun chalkboard(p: Params): ChalkboardParams = ChalkboardParams().let { d ->
        ChalkboardParams(
            tint = p.color("chalkboard.tint", d.tint),
            ghosting = p.float("chalkboard.ghosting", d.ghosting),
            dust = p.float("chalkboard.dust", d.dust),
        )
    }

    /** Lenient both ways: an unknown name is paper, the app's own default. */
    fun kindOf(name: String?): SurfaceKind =
        SurfaceKind.entries.firstOrNull { it.name == name } ?: SurfaceKind.PAPER
}
