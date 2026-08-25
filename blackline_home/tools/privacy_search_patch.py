from pathlib import Path

path = Path('blackline_home/src/main/java/online/pcguys/blackline/BlacklineHomeActivity.kt')
s = path.read_text(encoding='utf-8')

if 'private fun privacySearchBar()' not in s:
    old = '''        root.addView(commandHint(), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
        })
'''
    new = '''        root.addView(privacySearchBar(), FrameLayout.LayoutParams(-1, dp(54), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            leftMargin = dp(if (railCollapsed) 66 else 88)
            rightMargin = dp(20)
            bottomMargin = dp(76)
        })

        root.addView(commandHint(), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(28)
        })
'''
    if old not in s:
        raise SystemExit('renderHome insertion point not found')
    s = s.replace(old, new, 1)

    marker = '    private fun commandHint(): View = TextView(this).apply {'
    helper = r'''    private fun privacySearchBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.argb(188, 4, 5, 7), 20f, Color.argb(72, 255, 255, 255))
            elevation = dp(3).toFloat()
        }

        val providerChip = TextView(this).apply {
            text = searchProviderLabel(currentSearchProvider())
            gravity = Gravity.CENTER
            textSize = 8.2f
            letterSpacing = .08f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(150, 22, 24, 28), 14f, Color.argb(55, 255, 255, 255))
            setOnClickListener {
                val next = when (currentSearchProvider()) {
                    "BRAVE" -> "STARTPAGE"
                    "STARTPAGE" -> "DUCKDUCKGO"
                    else -> "BRAVE"
                }
                prefs.edit().putString("search_provider", next).apply()
                text = searchProviderLabel(next)
                toast("Private search: ${searchProviderName(next)}")
            }
            setOnLongClickListener {
                showSearchProviderPicker(this)
                true
            }
        }
        row.addView(providerChip, LinearLayout.LayoutParams(dp(58), dp(40)))

        val search = EditText(this).apply {
            hint = "Search privately"
            setHintTextColor(Color.rgb(150, 154, 160))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isSingleLine = true
            setPadding(dp(12), 0, dp(8), 0)
            background = ColorDrawable(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    submitPrivateSearch(text.toString())
                    true
                } else false
            }
        }
        row.addView(search, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })

        row.addView(TextView(this).apply {
            text = "↗"
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(150, 22, 24, 28), 14f, Color.argb(55, 255, 255, 255))
            setOnClickListener { submitPrivateSearch(search.text.toString()) }
        }, LinearLayout.LayoutParams(dp(42), dp(40)))

        return row
    }

    private fun currentSearchProvider(): String =
        prefs.getString("search_provider", "DUCKDUCKGO") ?: "DUCKDUCKGO"

    private fun searchProviderLabel(provider: String): String = when (provider) {
        "BRAVE" -> "BRAVE"
        "STARTPAGE" -> "START"
        else -> "DDG"
    }

    private fun searchProviderName(provider: String): String = when (provider) {
        "BRAVE" -> "Brave Search"
        "STARTPAGE" -> "Startpage"
        else -> "DuckDuckGo"
    }

    private fun showSearchProviderPicker(chip: TextView) {
        val values = arrayOf("DuckDuckGo", "Brave Search", "Startpage")
        val ids = arrayOf("DUCKDUCKGO", "BRAVE", "STARTPAGE")
        android.app.AlertDialog.Builder(this)
            .setTitle("Private search provider")
            .setItems(values) { _, which ->
                val chosen = ids[which]
                prefs.edit().putString("search_provider", chosen).apply()
                chip.text = searchProviderLabel(chosen)
            }
            .show()
    }

    private fun submitPrivateSearch(raw: String) {
        val query = raw.trim()
        if (query.isBlank()) return

        val direct = when {
            query.startsWith("https://", true) || query.startsWith("http://", true) -> query
            !query.contains(' ') && query.contains('.') -> "https://$query"
            else -> null
        }

        val target = direct ?: when (currentSearchProvider()) {
            "BRAVE" -> "https://search.brave.com/search?q=${Uri.encode(query)}"
            "STARTPAGE" -> "https://www.startpage.com/sp/search?query=${Uri.encode(query)}"
            else -> "https://duckduckgo.com/?q=${Uri.encode(query)}"
        }

        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure {
            toast("No browser available")
        }
    }

'''
    if marker not in s:
        raise SystemExit('commandHint marker not found')
    s = s.replace(marker, helper + marker, 1)

path.write_text(s, encoding='utf-8')
print('BLACKLINE privacy search patch applied')
