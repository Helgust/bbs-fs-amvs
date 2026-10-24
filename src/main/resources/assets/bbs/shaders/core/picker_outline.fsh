#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform int Target;
uniform vec4 HighlightColor;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

/* Outline width in stencil texels */
const int RADIUS = 2;

bool isTarget(ivec2 p)
{
    vec4 color = texelFetch(Sampler0, p, 0);

    if (color.a < 1.0)
    {
        return false;
    }

    int totalIndex = int(color.r * 255.0) | (int(color.g * 255.0) << 8) | (int(color.b * 255.0) << 16);

    return totalIndex == Target;
}

void main()
{
    ivec2 size = textureSize(Sampler0, 0);
    ivec2 p = min(ivec2(texCoord0 * vec2(size)), size - 1);

    if (isTarget(p))
    {
        discard;
    }

    for (int x = -RADIUS; x <= RADIUS; x++)
    {
        for (int y = -RADIUS; y <= RADIUS; y++)
        {
            ivec2 q = p + ivec2(x, y);

            /* Skip texels past the edge, so a model cut off by the screen gets no outline there */
            if (x * x + y * y <= RADIUS * RADIUS && all(greaterThanEqual(q, ivec2(0))) && all(lessThan(q, size)) && isTarget(q))
            {
                fragColor = HighlightColor * ColorModulator;

                return;
            }
        }
    }

    discard;
}
