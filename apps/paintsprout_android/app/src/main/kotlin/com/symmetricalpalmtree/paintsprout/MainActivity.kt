package com.symmetricalpalmtree.paintsprout

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsprout.databinding.ActivityMainBinding
import com.symmetricalpalmtree.paintsprout.paint.AVAILABLE_SURFACES
import com.symmetricalpalmtree.paintsprout.paint.SurfaceKind
import com.symmetricalpalmtree.paintsprout.paint.Tool

/**
 * Single launcher activity. Hosts [PaintCanvasView] with a bare-bones toolbar
 * (tools + surface cycle + clear) for verification. The real tool chrome (color
 * picker, surface picker) grows from here as it is ported from the Flutter
 * reference in apps/paintsprout_flutter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var surfaceIndex = AVAILABLE_SURFACES.indexOf(SurfaceKind.PAPER).coerceAtLeast(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPencil.setOnClickListener { binding.canvas.tool = Tool.PENCIL }
        binding.btnPen.setOnClickListener { binding.canvas.tool = Tool.PEN }
        binding.btnMarker.setOnClickListener { binding.canvas.tool = Tool.MARKER }
        binding.btnBrush.setOnClickListener { binding.canvas.tool = Tool.BRUSH }
        binding.btnWater.setOnClickListener { binding.canvas.tool = Tool.WATERCOLOR }
        binding.btnSpray.setOnClickListener { binding.canvas.tool = Tool.SPRAY }
        binding.btnEraser.setOnClickListener { binding.canvas.tool = Tool.ERASER }
        binding.btnClear.setOnClickListener { binding.canvas.clear() }
        binding.btnSurface.setOnClickListener { cycleSurface() }
        updateSurfaceLabel()
    }

    private fun cycleSurface() {
        surfaceIndex = (surfaceIndex + 1) % AVAILABLE_SURFACES.size
        binding.canvas.setSurface(AVAILABLE_SURFACES[surfaceIndex])
        updateSurfaceLabel()
    }

    private fun updateSurfaceLabel() {
        binding.btnSurface.text = "Surface: ${AVAILABLE_SURFACES[surfaceIndex].label}"
    }
}
