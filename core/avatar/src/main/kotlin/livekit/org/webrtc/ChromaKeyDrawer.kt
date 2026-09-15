@file:Suppress("PackageDirectoryMismatch")

package livekit.org.webrtc

import android.opengl.GLES20

/**
 * A video drawer that replaces the avatar's green backdrop with a flat colour of our choosing.
 *
 * # Why this is needed
 *
 * LiveAvatar renders the talking head against a chroma-key green screen and publishes that. Shown
 * raw, the customer sees a bright green rectangle with a face in it, which reads as a bug rather
 * than as a companion. Keying the green out and compositing on the app's own ground is what makes
 * the avatar look like part of the product.
 *
 * # Why it lives in `livekit.org.webrtc`
 *
 * `GlGenericDrawer` — the class that already knows how to bind OES, RGB and YUV textures, apply the
 * texture matrix and manage the shader lifecycle — is **package-private** in WebRTC's relocated
 * namespace. Re-implementing [RendererCommon.GlDrawer] from scratch to avoid this file's package
 * declaration would mean re-deriving three shader variants and the vertex plumbing, which is a much
 * larger surface to get subtly wrong than one unusual package name. So this file declares itself
 * into that package to reach the class, and does nothing else unusual.
 *
 * # Why it composites instead of producing alpha
 *
 * Writing transparency would require the `SurfaceView` behind the renderer to be translucent and
 * z-ordered as an overlay, which fights the rest of the layout and behaves differently across
 * vendors. Blending against a known background colour inside the shader gives the same picture with
 * none of that, because the stage's backdrop is a solid colour this app chooses anyway.
 *
 * ```
 * val drawer = chromaKeyDrawer(backgroundArgb = 0xFF0E0806.toInt())
 * renderer.init(eglContext, null, EglBase.CONFIG_PLAIN, drawer)
 * ```
 */
fun chromaKeyDrawer(
    backgroundArgb: Int,
    similarity: Float = DEFAULT_SIMILARITY,
    smoothness: Float = DEFAULT_SMOOTHNESS,
    spill: Float = DEFAULT_SPILL,
): RendererCommon.GlDrawer = GlGenericDrawer(
    CHROMA_KEY_FRAGMENT_SHADER,
    ChromaKeyShaderCallbacks(backgroundArgb, similarity, smoothness, spill),
)

/**
 * Feeds the shader its uniforms.
 *
 * Locations are looked up once per shader rather than per frame: `glGetUniformLocation` is a string
 * lookup into the driver, and this runs sixty times a second.
 */
private class ChromaKeyShaderCallbacks(
    backgroundArgb: Int,
    private val similarity: Float,
    private val smoothness: Float,
    private val spill: Float,
) : GlGenericDrawer.ShaderCallbacks {

    private val backgroundRgb = floatArrayOf(
        ((backgroundArgb shr 16) and 0xFF) / 255f,
        ((backgroundArgb shr 8) and 0xFF) / 255f,
        (backgroundArgb and 0xFF) / 255f,
    )

    private var keyLocation = 0
    private var similarityLocation = 0
    private var smoothnessLocation = 0
    private var spillLocation = 0
    private var backgroundLocation = 0

    override fun onNewShader(shader: GlShader) {
        keyLocation = shader.getUniformLocation("keyColor")
        similarityLocation = shader.getUniformLocation("similarity")
        smoothnessLocation = shader.getUniformLocation("smoothness")
        spillLocation = shader.getUniformLocation("spill")
        backgroundLocation = shader.getUniformLocation("background")
    }

    override fun onPrepareShader(
        shader: GlShader,
        texMatrix: FloatArray?,
        frameWidth: Int,
        frameHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        GLES20.glUniform2f(keyLocation, KEY_U, KEY_V)
        GLES20.glUniform1f(similarityLocation, similarity)
        GLES20.glUniform1f(smoothnessLocation, smoothness)
        GLES20.glUniform1f(spillLocation, spill)
        GLES20.glUniform3f(backgroundLocation, backgroundRgb[0], backgroundRgb[1], backgroundRgb[2])
    }

    private companion object {
        /**
         * Pure chroma green (0, 255, 0) expressed in the UV plane the shader compares against.
         *
         * Matching on chrominance alone, rather than on RGB distance, is what lets the key survive
         * the shadow and shading that fall across a real backdrop: a darker green is the same hue.
         */
        const val KEY_U = 0.070565f
        const val KEY_V = 0.081f
    }
}

/**
 * The shader body, appended to WebRTC's generated preamble.
 *
 * It must not re-declare `precision`, `varying vec2 tc`, or the sampler: `GlGenericDrawer` emits
 * those ahead of this text, along with the `sample()` function that hides whether the source is an
 * OES, RGB or YUV texture.
 */
private const val CHROMA_KEY_FRAGMENT_SHADER = """
uniform vec2 keyColor;
uniform float similarity;
uniform float smoothness;
uniform float spill;
uniform vec3 background;

// How much greener than red and blue a pixel must be before it is allowed to key at all.
#define GREEN_DOMINANCE_FLOOR 0.045

// Rec.601 chrominance. Hue and saturation survive here; brightness does not, which is exactly the
// property that makes a shadowed corner of the backdrop key out with the lit middle of it.
vec2 chroma(vec3 rgb) {
  return vec2(
    rgb.r * -0.169 + rgb.g * -0.331 + rgb.b *  0.500 + 0.5,
    rgb.r *  0.500 + rgb.g * -0.419 + rgb.b * -0.081 + 0.5
  );
}

void main() {
  vec4 src = sample(tc);

  float distance = distance(chroma(src.rgb), keyColor);
  // 0 deep in the backdrop, 1 well inside the subject, ramped across the edge so hair and the
  // shoulder line keep a soft boundary instead of a cut-out sticker's edge.
  float mask = clamp((distance - similarity) / smoothness, 0.0, 1.0);

  // Chrominance alone is not enough to be safe. Dark, near-neutral pixels — black hair above all —
  // carry so little colour that noise can drift their chrominance close to the key, and a similarity
  // high enough to catch a lit hairline will also swallow the hair itself. This gate says the
  // obvious thing the chroma test cannot: a backdrop pixel is one where green actually dominates
  // red and blue. Where it does not, the pixel is subject, whatever its chrominance suggests.
  //
  // Without it the hair keys out and is replaced by the stage colour — invisible when the stage is
  // near-black like the hair, and a flat slice through the forehead the moment the stage is grey.
  float greenness = src.g - max(src.r, src.b);
  float dominance = smoothstep(0.0, GREEN_DOMINANCE_FLOOR, greenness);
  mask = max(mask, 1.0 - dominance);

  // Green light bounces off the backdrop onto the subject, and it survives keying: the hair and
  // shoulder line come back rimmed in green. Desaturating toward luminance dulls the whole edge to
  // grey, which trades one artefact for another — so instead the green channel alone is capped at
  // what red and blue say it should be. Deep inside the subject (spillMask 1) nothing changes; at
  // the very edge (spillMask 0) green is clamped hard, and the rim takes the subject's own colour.
  float spillMask = clamp((distance - similarity) / spill, 0.0, 1.0);
  float limit = mix(src.r, src.b, 0.5);
  float green = min(src.g, mix(limit, src.g, spillMask));
  vec3 despilled = vec3(src.r, green, src.b);

  gl_FragColor = vec4(mix(background, despilled, mask), 1.0);
}
"""

/**
 * How close to the key hue still counts as backdrop.
 *
 * Tuned against LiveAvatar's own backdrops on a handset, not guessed. The binding case is hair:
 * a lit jacket edge keys out at 0.26, but the semi-transparent pixels along a spiky hairline are a
 * blend of hair and backdrop, so their chrominance sits partway between and survives a gentler key.
 * 0.34 catches those. It is deliberately lower than it once was: pushing it to 0.40 did kill the
 * fringe, but it also keyed the hair itself, which only looked acceptable while the stage behind it
 * happened to be as dark as the hair. The green-dominance gate in the shader is what makes the
 * gentler value safe.
 */
private const val DEFAULT_SIMILARITY = 0.34f

/**
 * The width of the ramp out of the backdrop. Wide enough that individual strands of hair fade rather
 * than alias into a hard sawtooth, narrow enough that the jacket's silhouette stays a silhouette.
 */
private const val DEFAULT_SMOOTHNESS = 0.12f

/** How far into the subject the green-limit reaches. Wide, because bounced light travels. */
private const val DEFAULT_SPILL = 0.50f
