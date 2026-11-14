package moe.takochan.machineradar.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 由精确方块集合算出两样东西：
 *
 * <ul>
 * <li>{@link #faces}：外壳外表面。先对扩展包围盒做一次外部泛洪，只有紧贴"能连通到外面的空气"
 * 的面才生成——机器内部的中空空气够不到外面，所以内壁面不会出现。</li>
 * <li>{@link #edges}：外表面的边。共面相邻（同一朝向）的格子线在生成阶段就丢掉；剩下的边
 * 带两个相邻面朝向，渲染时按摄像机位置判断是否为当前视角的外轮廓。</li>
 * </ul>
 */
public final class HullMesh {

    /** 每台机器最多生成多少面，防止超大结构拖慢帧率 */
    public static final int MAX_FACES = 8000;
    /** 包围盒超过这个格子数就不做外部泛洪，退回"邻接非结构方块"的判断 */
    public static final int MAX_FLOOD_CELLS = 200_000;

    /** 每 4 个 int 一个面：x, y, z, dir(0..5) */
    public final int[] faces;
    /** 每 6 个 int 一条外表面边：x0,y0,z0,x1,y1,z1（方块角点，整数坐标） */
    public final int[] edges;
    /** 每条边对应的外表面朝向位掩码（bit = dir），用于按视角算轮廓线 */
    public final int[] edgeDirs;
    /** 每条边被几个外表面共用：1 = 开边（结构被截断），2 = 正常，>2 = 非流形角 */
    public final int[] edgeFaces;
    /** 结构包围盒 minX,minY,minZ,maxX,maxY,maxZ */
    public final int[] bounds;

    private HullMesh(int[] faces, int[] edges, int[] edgeDirs, int[] edgeFaces, int[] bounds) {
        this.faces = faces;
        this.edges = edges;
        this.edgeDirs = edgeDirs;
        this.edgeFaces = edgeFaces;
        this.bounds = bounds;
    }

    public int faceCount() {
        return faces.length / 4;
    }

    public int edgeCount() {
        return edges.length / 6;
    }

    public static HullMesh of(int[][] blocks) {
        if (blocks.length == 0) {
            return new HullMesh(new int[0], new int[0], new int[0], new int[0], new int[] { 0, 0, 0, 0, 0, 0 });
        }
        Set<Long> set = new HashSet<Long>(Math.max(16, blocks.length * 2));
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < blocks.length; i++) {
            int[] b = blocks[i];
            set.add(pack(b[0], b[1], b[2]));
            if (b[0] < minX) minX = b[0];
            if (b[1] < minY) minY = b[1];
            if (b[2] < minZ) minZ = b[2];
            if (b[0] > maxX) maxX = b[0];
            if (b[1] > maxY) maxY = b[1];
            if (b[2] > maxZ) maxZ = b[2];
        }

        // 从包围盒外做一次泛洪，标出"外部空气"；只有紧贴外部空气的面才是外壳外表面。
        // 机器内部（中空）的空气够不到外面，所以内壁面不会生成——这就是"只显示最外层"。
        int ox = minX - 1, oy = minY - 1, oz = minZ - 1;
        int dx = maxX - minX + 3, dy = maxY - minY + 3, dz = maxZ - minZ + 3;
        boolean[] outside = null;
        if ((long) dx * dy * dz <= MAX_FLOOD_CELLS) {
            outside = floodOutside(set, ox, oy, oz, dx, dy, dz);
        }

        int[] tmp = new int[Math.min(MAX_FACES, blocks.length * 6) * 4];
        int n = 0;
        for (int i = 0; i < blocks.length; i++) {
            int[] b = blocks[i];
            int x = b[0], y = b[1], z = b[2];
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 0);
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 1);
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 2);
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 3);
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 4);
            n = addFace(tmp, n, set, outside, ox, oy, oz, dx, dy, dz, x, y, z, 5);
            if (n >= tmp.length) break;
        }

        int[] faces = new int[n];
        System.arraycopy(tmp, 0, faces, 0, n);

        int[][] edgeData = buildEdges(faces);
        return new HullMesh(
            faces,
            edgeData[0],
            edgeData[1],
            edgeData[2],
            new int[] { minX, minY, minZ, maxX, maxY, maxZ });
    }

    private static final int[] NX = { -1, 1, 0, 0, 0, 0 };
    private static final int[] NY = { 0, 0, -1, 1, 0, 0 };
    private static final int[] NZ = { 0, 0, 0, 0, -1, 1 };

    private static int addFace(int[] tmp, int n, Set<Long> set, boolean[] outside, int ox, int oy, int oz, int dx,
        int dy, int dz, int x, int y, int z, int dir) {
        int nx = x + NX[dir];
        int ny = y + NY[dir];
        int nz = z + NZ[dir];
        if (set.contains(pack(nx, ny, nz))) return n;

        if (outside != null) {
            int idx = ((nx - ox) * dy + (ny - oy)) * dz + (nz - oz);
            if (idx < 0 || idx >= outside.length || !outside[idx]) return n;
        }
        return add(tmp, n, x, y, z, dir);
    }

    /** 从扩展包围盒的边界开始 BFS，标记所有能连通到外面的空气格 */
    private static boolean[] floodOutside(Set<Long> set, int ox, int oy, int oz, int dx, int dy, int dz) {
        int volume = dx * dy * dz;
        boolean[] out = new boolean[volume];
        int[] queue = new int[volume];
        int head = 0;
        int tail = 0;

        for (int x = 0; x < dx; x++) {
            for (int y = 0; y < dy; y++) {
                for (int z = 0; z < dz; z++) {
                    if (x != 0 && x != dx - 1 && y != 0 && y != dy - 1 && z != 0 && z != dz - 1) continue;
                    if (set.contains(pack(ox + x, oy + y, oz + z))) continue;
                    int idx = (x * dy + y) * dz + z;
                    if (out[idx]) continue;
                    out[idx] = true;
                    queue[tail++] = idx;
                }
            }
        }

        while (head < tail) {
            int idx = queue[head++];
            int z = idx % dz;
            int rest = idx / dz;
            int y = rest % dy;
            int x = rest / dy;

            for (int dir = 0; dir < 6; dir++) {
                int nx = x + NX[dir];
                int ny = y + NY[dir];
                int nz = z + NZ[dir];
                if (nx < 0 || nx >= dx || ny < 0 || ny >= dy || nz < 0 || nz >= dz) continue;
                int nidx = (nx * dy + ny) * dz + nz;
                if (out[nidx]) continue;
                if (set.contains(pack(ox + nx, oy + ny, oz + nz))) continue;
                out[nidx] = true;
                queue[tail++] = nidx;
            }
        }
        return out;
    }

    /**
     * 面 → 边。每条边记录用到它的外表面朝向位掩码：
     * 边界边（1 个朝向）永远算轮廓；内部边（2 个朝向）由渲染器按视角判断是不是轮廓。
     */
    private static int[][] buildEdges(int[] faces) {
        Map<String, int[]> map = new HashMap<String, int[]>(Math.max(64, faces.length));
        for (int i = 0; i < faces.length; i += 4) {
            int x = faces[i], y = faces[i + 1], z = faces[i + 2], dir = faces[i + 3];
            switch (dir) {
                case 0:
                    faceEdges(map, dir, x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z);
                    break;
                case 1:
                    faceEdges(map, dir, x + 1, y, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x + 1, y, z + 1);
                    break;
                case 2:
                    faceEdges(map, dir, x, y, z, x + 1, y, z, x + 1, y, z + 1, x, y, z + 1);
                    break;
                case 3:
                    faceEdges(map, dir, x, y + 1, z, x, y + 1, z + 1, x + 1, y + 1, z + 1, x + 1, y + 1, z);
                    break;
                case 4:
                    faceEdges(map, dir, x, y, z, x, y + 1, z, x + 1, y + 1, z, x + 1, y, z);
                    break;
                default:
                    faceEdges(map, dir, x, y, z + 1, x + 1, y, z + 1, x + 1, y + 1, z + 1, x, y + 1, z + 1);
                    break;
            }
        }

        int[] out = new int[map.size() * 6];
        int[] dirsOut = new int[map.size()];
        int[] facesOut = new int[map.size()];
        int n = 0;
        for (int[] e : map.values()) {
            int faceCount = e[7];
            // 两个共面相邻的面之间的边永远不是轮廓，直接丢掉：省内存也省每帧遍历
            if (faceCount == 2 && Integer.bitCount(e[0]) <= 1) continue;
            out[n] = e[1];
            out[n + 1] = e[2];
            out[n + 2] = e[3];
            out[n + 3] = e[4];
            out[n + 4] = e[5];
            out[n + 5] = e[6];
            dirsOut[n / 6] = e[0];
            facesOut[n / 6] = faceCount;
            n += 6;
        }
        int[] edges = new int[n];
        int[] edgeDirs = new int[n / 6];
        int[] edgeFaces = new int[n / 6];
        System.arraycopy(out, 0, edges, 0, n);
        System.arraycopy(dirsOut, 0, edgeDirs, 0, n / 6);
        System.arraycopy(facesOut, 0, edgeFaces, 0, n / 6);
        return new int[][] { edges, edgeDirs, edgeFaces };
    }

    private static void faceEdges(Map<String, int[]> map, int dir, int ax, int ay, int az, int bx, int by, int bz,
        int cx, int cy, int cz, int dx, int dy, int dz) {
        addEdge(map, dir, ax, ay, az, bx, by, bz);
        addEdge(map, dir, bx, by, bz, cx, cy, cz);
        addEdge(map, dir, cx, cy, cz, dx, dy, dz);
        addEdge(map, dir, dx, dy, dz, ax, ay, az);
    }

    private static void addEdge(Map<String, int[]> map, int dir, int x0, int y0, int z0, int x1, int y1, int z1) {
        String a = x0 + "," + y0 + "," + z0;
        String b = x1 + "," + y1 + "," + z1;
        // 端点用字符串规范化，保证同一条边无论从哪个面加进来 key 都一样
        String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;

        int[] e = map.get(key);
        if (e == null) {
            e = new int[] { 0, x0, y0, z0, x1, y1, z1, 0 };
            map.put(key, e);
        }
        e[0] |= 1 << dir;
        e[7]++;
    }

    private static int add(int[] out, int n, int x, int y, int z, int dir) {
        if (n + 4 > out.length) return n;
        out[n] = x;
        out[n + 1] = y;
        out[n + 2] = z;
        out[n + 3] = dir;
        return n + 4;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFFL);
    }
}