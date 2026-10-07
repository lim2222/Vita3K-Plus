// Vita3K emulator project
// Copyright (C) 2026 Vita3K team
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

#version 450

layout(location = 0) in vec3 position_vertex;
layout(location = 1) in vec2 uv_vertex;

layout(location = 0) out vec2 uv_frag;

// Push constant for screen rotation (0=none, 1=90° CW, 2=180°, 3=270° CW)
layout(push_constant) uniform ScreenRotationPush {
    uint screen_rotation;
} push;

void main() {
    vec3 pos = position_vertex;
    vec2 uv = uv_vertex;
    
    // Apply screen rotation in vertex shader
    if (push.screen_rotation == 1) {
        // 90° clockwise: (x, y) -> (y, -x)
        pos = vec3(pos.y, -pos.x, pos.z);
        // UV rotation: (u, v) -> (v, 1-u) for 90° CW
        uv = vec2(uv.y, 1.0 - uv.x);
    } else if (push.screen_rotation == 2) {
        // 180°: (x, y) -> (-x, -y)
        pos = vec3(-pos.x, -pos.y, pos.z);
        // UV rotation: (u, v) -> (1-u, 1-v)
        uv = vec2(1.0 - uv.x, 1.0 - uv.y);
    } else if (push.screen_rotation == 3) {
        // 270° clockwise: (x, y) -> (-y, x)
        pos = vec3(-pos.y, pos.x, pos.z);
        // UV rotation: (u, v) -> (1-v, u)
        uv = vec2(1.0 - uv.y, uv.x);
    }
    
    gl_Position = vec4(pos, 1.0);
    uv_frag = uv;
}
