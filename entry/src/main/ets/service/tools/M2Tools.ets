// M2 文件读写 + 记忆库工具（B3/B4）：read_file / write_file / memory_save / memory_search
// 与 Android 端 M2Tools.kt 逐项对齐：错误形态（{"error":...}）、结果形态（{"saved":true,...}）、
// 沙箱边界（filesDir/agent_files，拒绝 .. 与绝对路径）、记忆文件（filesDir/agent_memory.json）。
// 统一契约与 BuiltinTools 一致：execute 失败一律返回 '{"error":...}' 字符串，不向上抛异常。
import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { AgentTool } from './AgentTool';

// 失败收敛 helper（形态与 BuiltinTools.err 一致）
function m2Err(msg: string): string {
  return JSON.stringify({ error: msg });
}

// args 解析 + 字段提取：非 string 字段一律视为缺失（与 Android m2Str 口径一致）。
// argsJson 非法 JSON 时抛异常，由各工具的 try/catch 收敛
function m2Field(argsJson: string, field: string): string {
  const obj = JSON.parse(argsJson) as Record<string, Object>;
  const v = obj[field];
  return typeof v === 'string' ? v as string : '';
}

// args 提取 string 字段（容忍缺失，返回空串）
function m2FieldOrEmpty(argsJson: string, field: string): string {
  try {
    return m2Field(argsJson, field);
  } catch (e) {
    return '';
  }
}

// 沙箱路径合成：拒绝绝对路径与 ..（含内嵌段），越界返回空串由调用方转 error
function safeJoin(root: string, rel: string): string {
  if (rel === '' || rel.startsWith('/') || rel.includes('..')) {
    return '';
  }
  return root + '/' + rel;
}

// 读文本文件：stat 量长 → open READ_ONLY → readSync 入 ArrayBuffer → TextDecoder 解码。
// 文件不存在/读取失败抛异常，由调用方 catch 收敛
function readTextFile(path: string): string {
  const stat = fs.statSync(path);
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const buf = new ArrayBuffer(stat.size);
    fs.readSync(file.fd, buf, { offset: 0, length: stat.size });
    return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
  } finally {
    fs.closeSync(file);
  }
}

// 写文本文件（覆盖）：mkdir 父目录 → open READ_WRITE|CREATE|TRUNC → writeSync → close
function writeTextFile(path: string, content: string): void {
  const dir = path.substring(0, path.lastIndexOf('/'));
  if (dir !== '' && !fs.accessSync(dir)) {
    fs.mkdirSync(dir, true);
  }
  const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  try {
    fs.writeSync(file.fd, content);
  } finally {
    fs.closeSync(file);
  }
}

// 记忆库读取已随忆枢 M5b 摘除（旧 KV json 迁移见 EntryAbility + memory/Migrator.ets）

export class M2Tools {
  // 工厂：文件两件（read_file/write_file）。忆枢后记忆五件迁至 memory/MemoryTools.ets，
  // 旧 KV memory_save/memory_search 已摘除（协议 §3，被五工具取代，存量 JSON 由 EntryAbility 一次性迁移）
  static createAll(ctx: common.Context): AgentTool[] {
    const root = ctx.filesDir + '/agent_files';
    if (!fs.accessSync(root)) {
      fs.mkdirSync(root, true);
    }
    const out: AgentTool[] = [];
    out.push(M2Tools.buildReadFile(root));
    out.push(M2Tools.buildWriteFile(root));
    return out;
  }

  // read_file：{path} → 沙箱内文本全文；越界/不存在 → error
  private static buildReadFile(root: string): AgentTool {
    const tool = new AgentTool();
    tool.name = 'read_file';
    tool.description = '读取沙箱内文件。参数：path。';
    tool.parametersJson = '{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const path = m2Field(argsJson, 'path');
        if (path === '') {
          return m2Err('缺少 path');
        }
        const full = safeJoin(root, path);
        if (full === '') {
          return m2Err('路径越界');
        }
        return readTextFile(full);
      } catch (e) {
        return m2Err(`文件读取失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  // write_file：{path, content} → 覆盖写入（父目录自动建）；越界/缺参 → error
  private static buildWriteFile(root: string): AgentTool {
    const tool = new AgentTool();
    tool.name = 'write_file';
    tool.description = '写入沙箱内文件（覆盖已有内容，父目录自动创建）。参数：path、content。';
    tool.parametersJson = '{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}';
    tool.execute = async (argsJson: string): Promise<string> => {
      try {
        const path = m2Field(argsJson, 'path');
        const content = m2FieldOrEmpty(argsJson, 'content');
        if (path === '') {
          return m2Err('缺少 path');
        }
        if (content === '') {
          return m2Err('缺少 content');
        }
        const full = safeJoin(root, path);
        if (full === '') {
          return m2Err('路径越界');
        }
        writeTextFile(full, content);
        return JSON.stringify({ written: true, path: path });
      } catch (e) {
        return m2Err(`文件写入失败: ${(e as Error).message}`);
      }
    };
    return tool;
  }

  // memory_save / memory_search 已随忆枢 M5b 摘除：新五工具见 memory/MemoryTools.ets，
  // 旧 agent_memory.json 由 EntryAbility 启动时一次性迁入 archival（协议 §1.5）
}
