package eu.kanade.tachiyomi.extension.zh.yamibo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class YamiboUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data != null && !data.encodedPath.isNullOrEmpty()) {
            var url = "https://" + data.host + data.encodedPath
            if (data.encodedQuery != null) {
                url += "?" + data.encodedQuery
            }
            Log.d("YamiboUrlActivity", "url: $url")
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", url)
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("YamiboUrlActivity", e.toString())
            }
        } else {
            Log.e("YamiboUrlActivity", "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
