#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require

precision highp float;

in vec2 vTexCoord;
out vec4 outColor;

uniform samplerExternalOES uTextureRGB;
uniform sampler2D uTextureDepth;
uniform vec2 u_resolution;
uniform float u_density;

// These can be adjusted for different visual effects
uniform float u_dotSizeFactor; 
uniform float u_distortionFactor;
uniform vec4 u_foregroundColor;
uniform vec4 u_backgroundColor;

void main() {
    // 1. Read Depth
    // The raw depth value, expected to be in a normalized range [0.0, 1.0]
    // 0.0 = far, 1.0 = near
    float depth = texture(uTextureDepth, vTexCoord).r;

    // 2. Calculate UV Displacement
    // Vector pointing from center (0.5, 0.5) to the current coordinate
    vec2 fromCenter = vTexCoord - 0.5;
    
    // The displacement is proportional to the depth and the distance from the center
    // Closer objects (higher depth) will have less distortion
    // The distortion strength is controlled by u_distortionFactor
    vec2 distortedUV = vTexCoord + fromCenter * (1.0 - depth) * u_distortionFactor;

    // 3. Grid Logic (using distorted UVs)
    float aspectRatio = u_resolution.x / u_resolution.y;
    vec2 gridScale = vec2(u_density, u_density);
    if (aspectRatio > 1.0) {
        gridScale.y /= aspectRatio;
    } else {
        gridScale.x *= aspectRatio;
    }

    vec2 gridUV = distortedUV * gridScale;
    vec2 gridCellCenter = floor(gridUV) + 0.5;
    vec2 sampleCoord = gridCellCenter / gridScale;

    // Ensure we don't sample outside the texture bounds after distortion
    if (sampleCoord.x < 0.0 || sampleCoord.x > 1.0 || sampleCoord.y < 0.0 || sampleCoord.y > 1.0) {
        outColor = u_backgroundColor;
        return;
    }

    // 4. Determine Dot Size and Color
    vec3 originalColor = texture(uTextureRGB, sampleCoord).rgb;
    float luma = dot(originalColor, vec3(0.299, 0.587, 0.114));
    
    // Dot size is inversely proportional to brightness
    float dotSize = (1.0 - luma) * u_dotSizeFactor;
    dotSize = 0.05 + pow(dotSize, 3.0) * 0.95; // Add some non-linearity

    // 5. Render the Dot
    vec2 cellUV = fract(gridUV) - 0.5;
    if (aspectRatio > 1.0) {
        cellUV.y *= aspectRatio;
    } else {
        cellUV.x /= aspectRatio;
    }

    float dist = length(cellUV) * 2.0; 
    float aa = 1.0 / u_density; // Anti-aliasing border
    float inShape = 1.0 - smoothstep(dotSize - aa, dotSize + aa, dist);

    outColor = mix(u_backgroundColor, u_foregroundColor, inShape);
}
