#!/usr/bin/env python3
import argparse
import base64
import json
import math
import shutil
import struct
from pathlib import Path

import numpy as np


COMPONENT_INFO = {
    5120: ("b", 1),   # BYTE
    5121: ("B", 1),   # UNSIGNED_BYTE
    5122: ("h", 2),   # SHORT
    5123: ("H", 2),   # UNSIGNED_SHORT
    5125: ("I", 4),   # UNSIGNED_INT
    5126: ("f", 4),   # FLOAT
}

TYPE_COMPONENTS = {
    "SCALAR": 1,
    "VEC2": 2,
    "VEC3": 3,
    "VEC4": 4,
    "MAT2": 4,
    "MAT3": 9,
    "MAT4": 16,
}


def parse_glb(path: Path):
    data = path.read_bytes()
    if len(data) < 20:
        raise ValueError(f"{path}: file too small")
    magic, version, total_len = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise ValueError(f"{path}: invalid GLB magic")
    if total_len != len(data):
        raise ValueError(f"{path}: header length mismatch")

    offset = 12
    gltf = None
    bin_chunk = b""
    while offset < len(data):
        chunk_len, chunk_type = struct.unpack_from("<II", data, offset)
        offset += 8
        chunk = data[offset:offset + chunk_len]
        offset += chunk_len
        if chunk_type == 0x4E4F534A:  # JSON
            text = chunk.decode("utf-8").rstrip("\x00").strip()
            gltf = json.loads(text)
        elif chunk_type == 0x004E4942:  # BIN
            bin_chunk = chunk
    if gltf is None:
        raise ValueError(f"{path}: missing JSON chunk")
    return gltf, bin_chunk


def quat_to_matrix(quat):
    x, y, z, w = quat
    xx = x * x
    yy = y * y
    zz = z * z
    xy = x * y
    xz = x * z
    yz = y * z
    wx = w * x
    wy = w * y
    wz = w * z
    return np.array([
        [1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy), 0.0],
        [2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx), 0.0],
        [2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy), 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ], dtype=np.float64)


def translation_matrix(t):
    m = np.eye(4, dtype=np.float64)
    m[0, 3], m[1, 3], m[2, 3] = t
    return m


def scale_matrix(s):
    m = np.eye(4, dtype=np.float64)
    m[0, 0], m[1, 1], m[2, 2] = s
    return m


def gltf_node_matrix(node):
    if "matrix" in node:
        # glTF stores matrices in column-major order.
        return np.array(node["matrix"], dtype=np.float64).reshape((4, 4), order="F")
    t = node.get("translation", [0.0, 0.0, 0.0])
    r = node.get("rotation", [0.0, 0.0, 0.0, 1.0])
    s = node.get("scale", [1.0, 1.0, 1.0])
    return translation_matrix(t) @ quat_to_matrix(r) @ scale_matrix(s)


def read_accessor(gltf, bin_chunk, accessor_index):
    accessor = gltf["accessors"][accessor_index]
    view = gltf["bufferViews"][accessor["bufferView"]]
    comp_type = accessor["componentType"]
    fmt_char, comp_size = COMPONENT_INFO[comp_type]
    num_comp = TYPE_COMPONENTS[accessor["type"]]
    count = accessor["count"]

    view_offset = view.get("byteOffset", 0)
    accessor_offset = accessor.get("byteOffset", 0)
    base = view_offset + accessor_offset
    stride = view.get("byteStride", num_comp * comp_size)
    item_size = num_comp * comp_size

    out = np.zeros((count, num_comp), dtype=np.float64)
    unpack_fmt = "<" + (fmt_char * num_comp)
    for i in range(count):
        chunk_start = base + i * stride
        values = struct.unpack_from(unpack_fmt, bin_chunk, chunk_start)
        out[i, :] = values
    return out


def read_indices(gltf, bin_chunk, accessor_index, vertex_count):
    if accessor_index is None:
        return np.arange(vertex_count, dtype=np.int64)
    arr = read_accessor(gltf, bin_chunk, accessor_index).reshape(-1)
    return arr.astype(np.int64)


def save_images(gltf, bin_chunk, out_dir: Path):
    images = gltf.get("images", [])
    saved = []
    for i, image in enumerate(images):
        mime = image.get("mimeType", "image/png")
        ext = ".png" if "png" in mime else ".jpg"
        out_name = f"texture_{i}{ext}"
        out_path = out_dir / out_name

        if "bufferView" in image:
            view = gltf["bufferViews"][image["bufferView"]]
            start = view.get("byteOffset", 0)
            end = start + view["byteLength"]
            out_path.write_bytes(bin_chunk[start:end])
        elif "uri" in image:
            uri = image["uri"]
            if uri.startswith("data:"):
                payload = uri.split(",", 1)[1]
                out_path.write_bytes(base64.b64decode(payload))
            else:
                src = out_dir / uri
                if src.exists():
                    shutil.copy2(src, out_path)
                else:
                    raise FileNotFoundError(f"Referenced image not found: {uri}")
        else:
            raise ValueError("Image has neither bufferView nor uri")
        saved.append(out_name)
    return saved


def build_materials(gltf, saved_images):
    out = []
    for i, mat in enumerate(gltf.get("materials", [])):
        name = mat.get("name") or f"material_{i}"
        pbr = mat.get("pbrMetallicRoughness", {})
        base = pbr.get("baseColorFactor", [1.0, 1.0, 1.0, 1.0])
        rgb = base[:3]
        tex = pbr.get("baseColorTexture")
        tex_name = None
        if tex is not None:
            tex_idx = tex.get("index")
            if tex_idx is not None:
                src_image = gltf.get("textures", [])[tex_idx]["source"]
                tex_name = saved_images[src_image]
        out.append({
            "name": name,
            "kd": rgb,
            "map_kd": tex_name,
        })
    if not out:
        out.append({"name": "material_0", "kd": [0.8, 0.8, 0.8], "map_kd": None})
    return out


def iter_scene_nodes(gltf):
    scenes = gltf.get("scenes", [])
    if not scenes:
        return
    scene_idx = gltf.get("scene", 0)
    scene = scenes[scene_idx]
    nodes = gltf.get("nodes", [])

    stack = []
    for root in scene.get("nodes", []):
        stack.append((root, np.eye(4, dtype=np.float64)))

    while stack:
        node_idx, parent_world = stack.pop()
        node = nodes[node_idx]
        local = gltf_node_matrix(node)
        world = parent_world @ local
        yield node_idx, node, world
        for child in reversed(node.get("children", [])):
            stack.append((child, world))


def sanitize_name(name):
    out = []
    for c in name.lower():
        if c.isalnum():
            out.append(c)
        elif c in (" ", "_", "-"):
            out.append("-")
    clean = "".join(out).strip("-")
    while "--" in clean:
        clean = clean.replace("--", "-")
    return clean or "model"


def convert_one(glb_path: Path, out_root: Path, forced_name=None):
    gltf, bin_chunk = parse_glb(glb_path)
    folder_name = forced_name or sanitize_name(glb_path.stem)
    out_dir = out_root / folder_name
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    saved_images = save_images(gltf, bin_chunk, out_dir)
    materials = build_materials(gltf, saved_images)

    positions = []
    normals = []
    uvs = []
    faces = []
    mat_names = [m["name"] for m in materials]

    meshes = gltf.get("meshes", [])
    for _, node, world in iter_scene_nodes(gltf):
        mesh_idx = node.get("mesh")
        if mesh_idx is None:
            continue
        mesh = meshes[mesh_idx]
        normal_matrix = np.linalg.inv(world[:3, :3]).T

        for prim in mesh.get("primitives", []):
            if prim.get("mode", 4) != 4:
                continue
            attrs = prim.get("attributes", {})
            if "POSITION" not in attrs:
                continue

            pos = read_accessor(gltf, bin_chunk, attrs["POSITION"])
            pos4 = np.concatenate([pos, np.ones((pos.shape[0], 1), dtype=np.float64)], axis=1)
            pos_w = (world @ pos4.T).T[:, :3]

            if "NORMAL" in attrs:
                n = read_accessor(gltf, bin_chunk, attrs["NORMAL"])
                n_w = (normal_matrix @ n.T).T
                lengths = np.linalg.norm(n_w, axis=1, keepdims=True)
                lengths[lengths == 0] = 1.0
                n_w = n_w / lengths
            else:
                n_w = np.zeros((pos.shape[0], 3), dtype=np.float64)

            if "TEXCOORD_0" in attrs:
                uv = read_accessor(gltf, bin_chunk, attrs["TEXCOORD_0"])
            else:
                uv = np.zeros((pos.shape[0], 2), dtype=np.float64)

            idx = read_indices(gltf, bin_chunk, prim.get("indices"), pos.shape[0])
            if idx.size % 3 != 0:
                tri_count = idx.size // 3
                idx = idx[:tri_count * 3]

            base_v = len(positions)
            base_vt = len(uvs)
            base_vn = len(normals)
            positions.extend(pos_w.tolist())
            uvs.extend(uv.tolist())
            normals.extend(n_w.tolist())

            mat_idx = prim.get("material", 0)
            if mat_idx < 0 or mat_idx >= len(mat_names):
                mat_idx = 0
            mat_name = mat_names[mat_idx]

            for t in range(0, idx.size, 3):
                i0 = int(idx[t])
                i1 = int(idx[t + 1])
                i2 = int(idx[t + 2])
                faces.append({
                    "verts": (base_v + i0 + 1, base_v + i1 + 1, base_v + i2 + 1),
                    "uvs": (base_vt + i0 + 1, base_vt + i1 + 1, base_vt + i2 + 1),
                    "normals": (base_vn + i0 + 1, base_vn + i1 + 1, base_vn + i2 + 1),
                    "mat": mat_name,
                })

    obj_name = "model.obj"
    mtl_name = "model.mtl"
    obj_path = out_dir / obj_name
    mtl_path = out_dir / mtl_name

    with obj_path.open("w", encoding="utf-8") as f:
        f.write(f"mtllib {mtl_name}\n")
        f.write(f"o {folder_name}\n")
        for v in positions:
            f.write(f"v {v[0]:.6f} {v[1]:.6f} {v[2]:.6f}\n")
        for vt in uvs:
            f.write(f"vt {vt[0]:.6f} {vt[1]:.6f}\n")
        for vn in normals:
            f.write(f"vn {vn[0]:.6f} {vn[1]:.6f} {vn[2]:.6f}\n")

        current_mat = None
        for face in faces:
            if face["mat"] != current_mat:
                current_mat = face["mat"]
                f.write(f"usemtl {current_mat}\n")
            v = face["verts"]
            t = face["uvs"]
            n = face["normals"]
            f.write(
                f"f {v[0]}/{t[0]}/{n[0]} {v[1]}/{t[1]}/{n[1]} {v[2]}/{t[2]}/{n[2]}\n"
            )

    with mtl_path.open("w", encoding="utf-8") as f:
        for m in materials:
            r, g, b = m["kd"]
            f.write(f"newmtl {m['name']}\n")
            f.write("Ns 0.000000\n")
            f.write("Ka 1.000000 1.000000 1.000000\n")
            f.write(f"Kd {r:.6f} {g:.6f} {b:.6f}\n")
            f.write("Ks 0.000000 0.000000 0.000000\n")
            f.write("Ke 0.000000 0.000000 0.000000\n")
            f.write("Ni 1.500000\n")
            f.write("d 1.000000\n")
            f.write("illum 2\n")
            if m["map_kd"] is not None:
                f.write(f"map_Kd {m['map_kd']}\n")
            f.write("\n")

    return out_dir


def main():
    parser = argparse.ArgumentParser(description="Convert GLB models to textured OBJ/MTL")
    parser.add_argument("--input", type=Path, default=Path("final-project/glb-files"))
    parser.add_argument("--output", type=Path, default=Path("final-project/models"))
    args = parser.parse_args()

    name_map = {
        "custom_red_panda.glb": "red-panda",
        "goblin_mob.glb": "goblin",
        "lucky_block.glb": "lucky-block",
        "minecraft_-_slime.glb": "slime",
        "mushroom_bup_minecraft_mob.glb": "mushroom-man",
    }

    args.output.mkdir(parents=True, exist_ok=True)
    converted = []
    for glb in sorted(args.input.glob("*.glb")):
        forced = name_map.get(glb.name)
        out_dir = convert_one(glb, args.output, forced_name=forced)
        converted.append(out_dir)
        print(f"Converted {glb.name} -> {out_dir}")

    print(f"Done. Converted {len(converted)} models.")


if __name__ == "__main__":
    main()
