package com.symmetricalpalmtree.paintsprout

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.paintsprout.databinding.ActivityMainBinding

/**
 * Single launcher activity. For the scaffold it simply hosts [PigmentCanvasView],
 * which proves the AGSL RuntimeShader pipeline renders end-to-end. The real tool
 * chrome and paint canvas will grow from here as features are ported from the
 * Flutter reference in apps/paintsprout_flutter.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
