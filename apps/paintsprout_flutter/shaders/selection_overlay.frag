#version 460 core
#include <flutter/runtime_effect.glsl>

// Magic-wand selection overlay: dims everything outside the selection and draws
// animated "marching ants" (a moving black/white dashed line) along the mask
// border. The border is found by comparing each pixel's mask value to its
// neighbors, so no contour extraction is needed.

precision highp float;

uniform vec2 uSize;   // overlay (display) size in px
uniform float uTime;  // 0..1, wraps once per animation period
uniform sampler2D uMask; // selection mask: alpha 1 = selected

out vec4 fragColor;

float selected(vec2 fc) {
  return texture(uMask, fc / uSize).a;
}

void main() {
  vec2 fc = FlutterFragCoord().xy;
  float m = selected(fc);

  // Border = a selected pixel that has a non-selected neighbor (within ~1.5px).
  const float s = 1.5;
  float border = 0.0;
  if (m > 0.5) {
    float mn = min(
        min(selected(fc + vec2(-s, 0.0)), selected(fc + vec2(s, 0.0))),
        min(selected(fc + vec2(0.0, -s)), selected(fc + vec2(0.0, s))));
    if (mn < 0.5) border = 1.0;
  }

  vec4 col = vec4(0.0);
  if (m < 0.5) {
    col = vec4(0.0, 0.0, 0.0, 0.26); // scrim over the unselected area
  }
  if (border > 0.5) {
    // Dashes march along the diagonal; travel one full period per uTime cycle
    // so the wrap from 1->0 is seamless.
    const float period = 10.0;
    float phase = mod(fc.x + fc.y - uTime * period, period);
    vec3 ant = phase < period * 0.5 ? vec3(0.0) : vec3(1.0);
    col = vec4(ant, 1.0);
  }

  fragColor = vec4(col.rgb * col.a, col.a); // premultiplied
}
