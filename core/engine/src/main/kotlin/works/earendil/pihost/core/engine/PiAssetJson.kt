package works.earendil.pihost.core.engine

import kotlinx.serialization.json.Json

/** 资产管道统一 Json 配置：清单未来加字段不破坏旧宿主。 */
internal val PiAssetJson: Json = Json { ignoreUnknownKeys = true }
