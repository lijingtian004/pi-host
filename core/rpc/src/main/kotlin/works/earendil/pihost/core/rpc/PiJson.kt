package works.earendil.pihost.core.rpc

import kotlinx.serialization.json.Json

/**
 * 入站解析配置（服务端→宿主，protocol.md §1/§7）。
 *
 * ignoreUnknownKeys：pi 版本演进新增字段不得打断宿主——宽松解析是对
 * “零改动跑官方 bundle”这一约束的直接回应：协议漂移只能靠 golden 回放
 * 测试显式暴露，而不是让运行时崩掉。
 */
val PiJsonIn: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * 出站序列化配置（宿主→服务端）。
 *
 * encodeDefaults=false：命令里 `id: String? = null`、可选字段一律不写线，
 * 与 capture.js 抓包样本保持字节级一致。
 */
val PiJsonOut: Json = Json {
    encodeDefaults = false
}
