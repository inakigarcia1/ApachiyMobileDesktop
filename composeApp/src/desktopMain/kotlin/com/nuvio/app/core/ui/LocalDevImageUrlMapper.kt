package com.nuvio.app.core.ui

import coil3.map.Mapper
import coil3.request.Options
import com.nuvio.app.core.network.rewriteLocalDevUrl

internal class LocalDevImageUrlMapper : Mapper<String, String> {
    override fun map(data: String, options: Options): String = rewriteLocalDevUrl(data) ?: data
}
