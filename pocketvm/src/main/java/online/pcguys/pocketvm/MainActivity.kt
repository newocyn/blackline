package online.pcguys.pocketvm

import android.app.*
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity: AppCompatActivity() {
 private lateinit var list: LinearLayout
 private val prefs by lazy { getSharedPreferences("vms", MODE_PRIVATE) }
 override fun onCreate(b: Bundle?) { super.onCreate(b); window.statusBarColor=Color.rgb(5,6,8); render() }
 private fun tv(s:String, sp:Float, bold:Boolean=false)=TextView(this).apply { text=s; textSize=sp; setTextColor(Color.WHITE); setPadding(0,8,0,8); if(bold) setTypeface(typeface,Typeface.BOLD) }
 private fun render(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(36,28,36,24); setBackgroundColor(Color.rgb(5,6,8))}
  root.addView(tv("PCG // POCKETVM",28f,true)); root.addView(tv("PORTABLE VIRTUAL MACHINE HOST",12f))
  val status=tv("● ENGINE  SOFTWARE EMULATION  •  ARM64 READY",11f,true).apply{setTextColor(Color.CYAN)}; root.addView(status)
  val create=Button(this).apply{text="＋ CREATE VIRTUAL MACHINE"; setOnClickListener{dialog()}}; root.addView(create)
  root.addView(tv("MACHINES",14f,true)); list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}; root.addView(list)
  val info=tv("PocketVM v0.1\nVM manager foundation • ISO/IMG import and QEMU runtime integration next",12f); root.addView(info)
  setContentView(ScrollView(this).apply{addView(root)}); refresh()
 }
 private fun refresh(){ list.removeAllViews(); val names=prefs.getStringSet("names", emptySet())!!.toList(); if(names.isEmpty()) list.addView(tv("NO MACHINES\nCreate your first portable VM.",16f,true)); else names.forEach{n->
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(22,18,22,18); setBackgroundColor(Color.rgb(18,21,25))}
   card.addView(tv("◈  $n",20f,true)); card.addView(tv("OFFLINE  •  ARM64  •  2 CPU  •  2048 MB RAM  •  16 GB DISK",12f))
   val row=LinearLayout(this); row.addView(Button(this).apply{text="BOOT"; setOnClickListener{Toast.makeText(this@MainActivity,"Runtime engine will attach here",Toast.LENGTH_SHORT).show()}},LinearLayout.LayoutParams(0,-2,1f)); row.addView(Button(this).apply{text="DELETE"; setOnClickListener{ val s=prefs.getStringSet("names", emptySet())!!.toMutableSet(); s.remove(n); prefs.edit().putStringSet("names",s).apply(); refresh()}},LinearLayout.LayoutParams(0,-2,1f)); card.addView(row); list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,8,0,14)})
  }
 }
 private fun dialog(){ val input=EditText(this).apply{hint="Machine name"}; AlertDialog.Builder(this).setTitle("CREATE VM").setView(input).setMessage("ARM64 • 2 CPU • 2048 MB • 16 GB\nInitial profile").setPositiveButton("CREATE"){_,_-> val n=input.text.toString().trim().ifEmpty{"Alpine VM"}; val s=prefs.getStringSet("names", emptySet())!!.toMutableSet(); s.add(n); prefs.edit().putStringSet("names",s).apply(); refresh()}.setNegativeButton("CANCEL",null).show() }
}
