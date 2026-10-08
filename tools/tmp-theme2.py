import io

p = 'app/src/main/java/com/nukacast/app/MainActivity.java'
s = io.open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')


def swap(old, new):
    global s
    assert old in s, 'MISSING: ' + old[:130]
    s = s.replace(old, new, 1)


# 1) The live entry belongs to the sidebar and has to be themed with it. It was missing from this list, so in
#    the light theme its icon stayed white on a white sheet — what the viewer reported as "the icons are gone".
swap("""                boolean primaryNavigation = id == R.id.navHome || id == R.id.navMovies
                        || id == R.id.navCast || id == R.id.navSettings;""",
     """                boolean primaryNavigation = id == R.id.navHome || id == R.id.navMovies
                        || id == R.id.navLive || id == R.id.navCast || id == R.id.navSettings;""")
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# 2) The same list in the layout half: TvTheme decides by id, so the live tile also needs the shared style.
p = 'app/src/main/res/values/nav_styles.xml'
s = io.open(p, encoding='utf-8', newline='').read().replace('\r\n', '\n')
print('nav_styles.xml:')
print(s[:400])
