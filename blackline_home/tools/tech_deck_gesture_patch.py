from pathlib import Path

path = Path('blackline_home/src/main/java/online/pcguys/blackline/BlacklineHomeActivity.kt')
s = path.read_text(encoding='utf-8')

if 'private fun techCornerHint()' not in s:
    old = '''        root.addView(brandMark(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(16)
            topMargin = dp(18)
        })
'''
    new = '''        root.addView(techCornerHint(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(10)
            bottomMargin = dp(12)
        })

        root.addView(brandMark(), FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(16)
            topMargin = dp(18)
        })
'''
    if old not in s:
        raise SystemExit('brand insertion point not found')
    s = s.replace(old, new, 1)

    marker = '    private fun brandMark(): View = TextView(this).apply {'
    helper = '''    private fun techCornerHint(): View = TextView(this).apply {
        text = "↖ TECH"
        gravity = Gravity.CENTER
        textSize = 7f
        letterSpacing = .10f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.argb(185, 255, 255, 255))
        setPadding(dp(9), dp(6), dp(9), dp(6))
        background = rounded(Color.argb(76, 0, 0, 0), 14f, Color.argb(38, 255, 255, 255))
        setOnClickListener { startActivity(Intent(this@BlacklineHomeActivity, TechToolsActivity::class.java)) }
    }

'''
    if marker not in s:
        raise SystemExit('brand helper marker not found')
    s = s.replace(marker, helper + marker, 1)

if 'BOTTOM-RIGHT DIAGONAL' not in s:
    old = '''            val threshold = dp(70).toFloat()

            if (abs(dy) > abs(dx) && dy < -threshold) {
'''
    new = '''            val threshold = dp(70).toFloat()

            // BOTTOM-RIGHT DIAGONAL: pull up-left from the corner into TECH DECK.
            // Check this first so it cannot be mistaken for All Apps or Quick Pod.
            val fromTechCorner = e1.x > resources.displayMetrics.widthPixels * .72f &&
                e1.y > resources.displayMetrics.heightPixels * .72f
            if (fromTechCorner && dx < -threshold * .62f && dy < -threshold * .62f) {
                startActivity(Intent(this@BlacklineHomeActivity, TechToolsActivity::class.java))
                return true
            }

            if (abs(dy) > abs(dx) && dy < -threshold) {
'''
    if old not in s:
        raise SystemExit('gesture insertion point not found')
    s = s.replace(old, new, 1)

# Make the existing bottom hint mention the diagonal gesture without making it longer than necessary.
s = s.replace(
    'text = "↑ APPS     RIGHT EDGE → QUICK POD     ↓ DECK"',
    'text = "↑ APPS     RIGHT → QUICK POD     ↓ DECK"',
    1
)

path.write_text(s, encoding='utf-8')
print('BLACKLINE Tech Deck diagonal gesture patch applied')
