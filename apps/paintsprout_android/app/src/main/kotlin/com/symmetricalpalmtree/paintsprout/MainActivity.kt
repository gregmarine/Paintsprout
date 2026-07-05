package com.symmetricalpalmtree.paintsprout

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsprout.databinding.ActivityMainBinding
import com.symmetricalpalmtree.paintsprout.paint.Tool

/**
 * Single launcher activity. Hosts [PaintCanvasView] with a bare-bones toolbar
 * (pen / eraser / clear) for Stage 2 verification. The real tool chrome and the
 * remaining features grow from here as they are ported from the Flutter
 * reference in apps/paintsprout_flutter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPen.setOnClickListener { binding.canvas.tool = Tool.PEN }
        binding.btnEraser.setOnClickListener { binding.canvas.tool = Tool.ERASER }
        binding.btnClear.setOnClickListener { binding.canvas.clear() }
    }
}
