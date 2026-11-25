package link.darmok.dict

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** Owns the database: every [Dict] call runs here, in order. */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var dict: Dict? = null

    /** Bumped on every keystroke, so that results for an older query are dropped. */
    @Volatile
    private var generation = 0

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private val adapter = ResultAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        padForSystemBars(findViewById(R.id.root))

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = adapter.getItem(position)) {
                is FormHint -> setQuery(row.lemma)
                is Hit -> showLine(row)
            }
        }
        findViewById<View>(R.id.clear).setOnClickListener { setQuery("") }
        findViewById<View>(R.id.about).setOnClickListener { showAbout() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = search()
        })

        status.setText(R.string.preparing)
        worker.execute {
            try {
                dict = Dict.open(applicationContext)
                main.post { search() }
            } catch (e: Exception) {
                main.post { status.setText(R.string.prepare_failed) }
            }
        }
        if (savedInstanceState == null) useSelectedText(intent)
        input.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        useSelectedText(intent)
    }

    override fun onDestroy() {
        generation++
        worker.shutdown()
        super.onDestroy()
    }

    /** Text sent from another app's selection menu. */
    private fun useSelectedText(intent: Intent) {
        if (intent.action != Intent.ACTION_PROCESS_TEXT) return
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.let { setQuery(it.toString().trim()) }
    }

    private fun setQuery(text: String) {
        input.setText(text)
        input.setSelection(input.text.length)
    }

    private fun search() {
        val query = input.text.toString()
        val mine = ++generation
        if (query.isBlank()) {
            if (dict != null) show(query, Results(emptyList(), emptyList()))
            return
        }
        worker.execute {
            val d = dict ?: return@execute
            if (mine != generation) return@execute
            val results = d.search(query)
            main.post { if (mine == generation) show(query, results) }
        }
    }

    private fun show(query: String, results: Results) {
        adapter.rows = results.hints + results.hits
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        status.text = when {
            query.isBlank() -> getString(R.string.intro)
            adapter.rows.isEmpty() -> getString(R.string.no_results, query.trim())
            else -> ""
        }
    }

    /** The whole Ding line a result belongs to, with the tapped sub-entry in bold. */
    private fun showLine(hit: Hit) {
        worker.execute {
            val pairs = dict?.line(hit.lineId) ?: return@execute
            val text = SpannableStringBuilder()
            for ((i, pair) in pairs.withIndex()) {
                if (i > 0) text.append("\n\n")
                val start = text.length
                text.append(annotated(pair.first, muted())).append('\n')
                if (i == hit.sub) {
                    text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.append(annotated(pair.second, muted()))
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                val dialog = AlertDialog.Builder(this)
                    .setMessage(text)
                    .setPositiveButton(R.string.close, null)
                    .show()
                dialog.findViewById<TextView>(android.R.id.message)?.let {
                    it.setTextColor(getColor(R.color.fg))
                    it.setTextIsSelectable(true)
                }
            }
        }
    }

    private fun showAbout() {
        worker.execute {
            val d = dict ?: return@execute
            val text = getString(
                R.string.about_text, d.meta("ding_version"), d.meta("lines"), d.meta("forms")
            )
            main.post {
                if (isFinishing || isDestroyed) return@post
                AlertDialog.Builder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(text)
                    .setPositiveButton(R.string.close, null)
                    .show()
                    .findViewById<TextView>(android.R.id.message)?.setTextColor(getColor(R.color.fg))
            }
        }
    }

    private fun muted(): Int = getColor(R.color.fg_muted)

    /** Greys out Ding's annotations: {grammar}, [region or field] and (comments). */
    private fun annotated(text: String, color: Int): SpannableStringBuilder {
        val out = SpannableStringBuilder(text)
        for (m in ANNOTATION.findAll(text)) {
            out.setSpan(
                ForegroundColorSpan(color), m.range.first, m.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return out
    }

    /** Android 15 draws apps behind the status bar, navigation bar and keyboard. */
    private fun padForSystemBars(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private inner class ResultAdapter : BaseAdapter() {
        var rows: List<Any> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is FormHint) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val layout = if (row is FormHint) R.layout.row_hint else R.layout.row_hit
            val view = convertView ?: layoutInflater.inflate(layout, parent, false)
            when (row) {
                is FormHint -> {
                    val typed = input.text.toString().trim()
                    val kind = Dict.KINDS.getOrElse(row.kind) { Dict.KINDS[0] }
                    val text = SpannableStringBuilder(getString(R.string.form_of, typed, kind, row.lemma))
                    val lemma = text.length - row.lemma.length
                    text.setSpan(StyleSpan(Typeface.BOLD), lemma, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(
                        ForegroundColorSpan(getColor(R.color.lemma)), lemma, text.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    (view as TextView).text = text
                }
                is Hit -> {
                    view.findViewById<TextView>(R.id.de).text = annotated(row.de, muted())
                    view.findViewById<TextView>(R.id.en).text = annotated(row.en, muted())
                }
            }
            return view
        }
    }

    private companion object {
        val ANNOTATION = Regex("\\{[^}]*\\}|\\[[^\\]]*\\]|\\([^)]*\\)")
    }
}
