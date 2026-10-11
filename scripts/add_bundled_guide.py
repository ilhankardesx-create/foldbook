from pathlib import Path
import zipfile

ROOT = Path('.')
ASSET = ROOT / 'app/src/main/assets/books/foldbook_kullanim_kilavuzu.epub'
MAIN = ROOT / 'app/src/main/java/com/foldbook/app/MainActivity.kt'

chapters = [
    (
        'Hoş Geldin',
        '''FoldBook'a hoş geldin. Bu küçük kitap uygulamanın temel özelliklerini tanıtmak için FoldBook ile birlikte gelir. Kılavuzun kendisi de gerçek bir EPUB dosyasıdır; yani okurken kullandığın sayfa çevirme, tema, yazı boyutu, not ve vurgulama özelliklerini bunun üzerinde deneyebilirsin.

FoldBook özellikle katlanabilir cihazlarda açık bir kitap hissi vermek için tasarlandı. Telefon tek ekran olarak kullanıldığında tek sayfa, uygun katlanabilir ekranda ise karşılıklı iki sayfa görünümü kullanılır. Amaç, menülerle uğraştırmadan kitabı açıp okumaya başlamanı sağlamaktır.

Okuma sırasında ekrana bir kez dokunarak hızlı kontrolleri açabilir, yeniden dokunarak kapatabilirsin. Okuma konumun kaydedilir; kitabı kapatıp daha sonra açtığında kaldığın yere dönersin.'''
    ),
    (
        'Kitaplarını FoldBook\'a Ekle',
        '''Kütüphanedeki Ayarlar bölümünden “Kitapların Olduğu Klasörü Seç” düğmesine dokun. Android'in klasör seçicisi açıldığında EPUB ve PDF kitaplarını tuttuğun klasörü seçmen yeterlidir. FoldBook seçtiğin klasörü hatırlar ve sonraki açılışlarda aynı kitapları rafa yerleştirir. Klasöre yeni bir kitap eklediğinde Yenile düğmesini kullanabilirsin.

Bir EPUB veya PDF dosyasına dosya yöneticinden dokunduğunda Android sana uygun uygulamaları gösterirse “FoldBook ile aç” seçeneğini de kullanabilirsin. Bu yöntem, tek bir kitabı hızlıca FoldBook'ta açmak için pratiktir.

FoldBook telefonundaki her klasörü kendi kendine dolaşmaz. Hangi kitap klasörüne erişeceğine sen karar verirsin. Böylece uygulama yalnızca okumak için seçtiğin dosyalarla ilgilenir.'''
    ),
    (
        'Dosya Erişimi ve Gizlilik',
        '''FoldBook neden “Tüm dosyalara erişim” izni istemiyor? Çünkü bir e-kitap okuyucunun fotoğraflarına, videolarına ve cihazındaki diğer kişisel dosyalara sınırsız erişmesine gerek olmadığına inanıyoruz.

Android'in modern depolama sistemi uygulamaların yalnızca ihtiyaç duydukları dosyalara erişmesini teşvik eder. FoldBook da bu yaklaşıma uyar: kitap klasörünü sen seçersin ve uygulama bu seçim üzerinden çalışır. Bu nedenle cihazdaki bütün depolama alanını otomatik tarayan bir özellik bulunmaz.

Bu tercih, daha az izin isteyen, daha sade ve daha güvenli bir kullanım sağlar. Kitapların farklı klasörlerdeyse istediğin zaman kitap klasörü seçimini değiştirebilirsin. Tek bir dosyayı açmak içinse “FoldBook ile aç” yöntemi her zaman kullanılabilir.'''
    ),
    (
        'EPUB mu PDF mi?',
        '''FoldBook hem EPUB hem de PDF dosyalarını destekler. Ancak mümkün olduğunda EPUB kullanırsan daha iyi bir okuma deneyimi elde edersin.

EPUB içeriği esnektir. Metin ekran boyutuna göre yeniden düzenlenebilir, yazı boyutu değiştirilebilir ve farklı ekran oranlarına daha doğal uyum sağlar. Özellikle katlanabilir cihazlarda ve ekran yönünü değiştirdiğinde bu esneklik gerçek kitap hissini güçlendirir.

PDF ise sabit sayfa düzenini korur. Bir PDF sayfasındaki yazı, görsel ve boşluklar dosyada nasıl hazırlanmışsa FoldBook bunları mümkün olduğunca aynı düzenle gösterir. Bu yüzden bazı PDF'lerde küçük ekranlarda yazılar daha ufak görünebilir veya sayfanın oranı ekrandan farklı olabilir.

Kısacası: roman, hikâye ve uzun metinler için EPUB; özgün sayfa tasarımının korunması gereken belge, dergi veya taranmış eserler için PDF daha uygundur.'''
    ),
    (
        'Sayfa Çevirme ve Katlanabilir Ekran',
        '''Sayfayı sağdan sola sürüklediğinde bir sonraki sayfaya, soldan sağa sürüklediğinde önceki sayfaya geçebilirsin. Sayfayı yarıda bırakıp geri dönersen FoldBook sayfayı yeniden yerine getirir. Hareketi tamamladığında kıvrılma animasyonu yumuşak biçimde bitirilir.

Katlanabilir cihaz açık durumdayken uygun ekran oranında iki sayfa yan yana görünür. Ortadaki kat izi kitabın sırtı gibi davranır. Cihaz kapatıldığında veya dar ekrana geçildiğinde okuyucu tek sayfalı düzene uyum sağlar.

Ekranı dikeyden yataya çevirdiğinde FoldBook mümkün olduğunca aynı okuma noktasını korur. Böylece yalnızca yön değiştirdiğin için başka bir paragrafa atlamaman hedeflenir.'''
    ),
    (
        'Metin Seçme, Sarı Fosfor ve Notlar',
        '''EPUB okurken bir kelimeye veya metne basılı tutarak seçim yapabilirsin. Seçim işaretçilerini sürükleyerek başlangıç ve bitiş noktasını ayarla.

Seçtiğin bölümü “Sarı Fosforla Çiz” seçeneğiyle vurgulayabilirsin. İşaretlenen metin kitapta sarı fosforla çizilmiş gibi görünür ve daha sonra aynı kitaba döndüğünde vurguların korunur. Önemli bir cümleyi, alıntıyı veya daha sonra tekrar bakmak istediğin bölümü bu şekilde işaretleyebilirsin.

Aynı metin seçiminden “Notlara Ekle” seçeneğini kullanırsan seçili bölüm not olarak kaydedilir. Kütüphanenin üst kısmındaki Notlar bölümünden kaydettiğin parçaları tekrar görebilir ve istemediklerini silebilirsin.

İpucu: Bu kılavuz da bir EPUB olduğu için şu an okuduğun herhangi bir cümleyi seçip sarı fosfor ve not özelliğini doğrudan deneyebilirsin.'''
    ),
    (
        'Tema, Yazı Boyutu ve Sesli Okuma',
        '''Ayarlar bölümünden EPUB yazı boyutunu Küçük, Orta veya Büyük olarak değiştirebilirsin. EPUB'un yeniden akışkan yapısı sayesinde metin yeni boyuta göre tekrar sayfalara yerleştirilir.

Açık, Sepya ve Koyu tema seçenekleri farklı ışık koşullarında daha rahat okumak için kullanılabilir. Gece okurken koyu tema, uzun süreli okumalarda ise sepya görünüm hoşuna gidebilir.

FoldBook'un sesli okuma özelliği cihazındaki yerel metinden sese motorunu kullanır. Uygun EPUB metnini sesli dinleyebilir, okuma devam ederken sayfaların ilerlemesini sağlayabilirsin. Kullanılabilir sesler ve telaffuz kalitesi cihazındaki Android ses motoruna göre değişebilir.'''
    ),
    (
        'Kaldığın Yerden Devam Et',
        '''FoldBook okuduğun kitabın sayfa konumunu kaydeder. Kütüphaneye döndüğünde son okuduğun kitap rafta belirgin biçimde gösterilir ve yeniden açtığında kaldığın yere yakın noktadan devam edersin.

Kütüphanede kitap adına göre arama yapabilirsin. Bir kitaba uzun basarak favorilere ekleme gibi kitap işlemlerine ulaşabilirsin. Kendi kitaplarında yeniden adlandırma ve silme seçenekleri de kullanılabilir.

Bu FoldBook Kullanım Kılavuzu uygulamanın kendi kitabıdır. Yanlışlıkla kaybolmaması için yeniden adlandırma ve silme işlemleri bu kitapta gösterilmez.'''
    ),
    (
        'Reklamsız ve Özgür Okuma',
        '''FoldBook reklamsızdır ve sonsuza kadar reklamsız kalacaktır. Okuma ekranında, kütüphanede veya sayfa geçişlerinin arasında reklam gösterilmeyecektir.

Uygulamanın temel özelliklerini kullanmak için ödeme yapmak zorunda değilsin. Okumak, EPUB ve PDF açmak, tema kullanmak, not almak veya sarı fosforla işaretlemek reklam izlemene bağlı değildir.

FoldBook'u sevdiysen ve geliştirilmesine gönüllü olarak katkıda bulunmak istersen uygulamadaki Destek bölümünü kullanabilirsin. Destek tamamen isteğe bağlıdır; sana ekstra özellik açmaz ve destek vermeyen kullanıcıdan hiçbir özelliği eksiltmez. Ama küçük bir destek bırakman beni gerçekten mutlu eder. ❤️'''
    ),
    (
        'Küçük İpuçları',
        '''En rahat deneyim için uzun metinli kitaplarda EPUB formatını tercih et. Kitaplarını tek bir klasörde tutarsan klasörü bir kez seçmen ve daha sonra yalnızca Yenile düğmesine dokunman yeterli olur.

Okurken arayüz dikkatini dağıtıyorsa ekrana dokunarak kontrolleri kapat. Önemli yerleri sarı fosforla işaretle, daha uzun parçaları Notlara ekle ve sesli okumayı uygun kitaplarda dene.

FoldBook sade tutulmaya çalışılır. Bir özelliğin neden karmaşık menüler içinde olmadığını merak edersen bunun nedeni çoğu zaman aynı: kitabı açmak, sayfayı çevirmek ve okumak uygulamanın merkezinde kalsın.

İyi okumalar. 📖'''
    ),
]

ASSET.parent.mkdir(parents=True, exist_ok=True)

container = '''<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
'''

manifest_items = []
spine_items = []
nav_items = []
chapter_files = {}

for i, (title, body) in enumerate(chapters, start=1):
    ident = f'ch{i:02d}'
    filename = f'{ident}.xhtml'
    manifest_items.append(f'    <item id="{ident}" href="{filename}" media-type="application/xhtml+xml"/>')
    spine_items.append(f'    <itemref idref="{ident}"/>')
    nav_items.append(f'        <li><a href="{filename}">{title}</a></li>')
    paragraphs = ''.join(f'    <p>{p}</p>\n' for p in body.split('\n\n'))
    chapter_files[f'OEBPS/{filename}'] = f'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" lang="tr" xml:lang="tr">
<head>
  <title>{title}</title>
  <meta charset="UTF-8"/>
</head>
<body>
{paragraphs}</body>
</html>
'''

opf = f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:foldbook-kullanim-kilavuzu-v1</dc:identifier>
    <dc:title>FoldBook Kullanım Kılavuzu</dc:title>
    <dc:language>tr</dc:language>
    <dc:creator>FoldBook</dc:creator>
    <meta property="dcterms:modified">2026-10-11T17:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
{chr(10).join(manifest_items)}
  </manifest>
  <spine>
{chr(10).join(spine_items)}
  </spine>
</package>
'''

nav = f'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="tr" xml:lang="tr">
<head><title>İçindekiler</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>İçindekiler</h1>
    <ol>
{chr(10).join(nav_items)}
    </ol>
  </nav>
</body>
</html>
'''

with zipfile.ZipFile(ASSET, 'w') as z:
    z.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)
    z.writestr('META-INF/container.xml', container, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr('OEBPS/content.opf', opf, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr('OEBPS/nav.xhtml', nav, compress_type=zipfile.ZIP_DEFLATED)
    for path, data in chapter_files.items():
        z.writestr(path, data, compress_type=zipfile.ZIP_DEFLATED)

s = MAIN.read_text()

old = '    var library by remember { mutableStateOf<List<LibraryBook>>(emptyList()) }'
new = '    var library by remember { mutableStateOf(BundledGuide.withGuide(context, emptyList())) }'
if old not in s:
    raise SystemExit('initial library state anchor not found')
s = s.replace(old, new, 1)

old = '''            }.onSuccess {
                library = it
            }.onFailure {'''
new = '''            }.onSuccess { found ->
                library = BundledGuide.withGuide(context, found)
            }.onFailure {'''
if old not in s:
    raise SystemExit('scanFolder success anchor not found')
s = s.replace(old, new, 1)

old = '''                    Button(
                        onClick = {
                            renameBookTarget = book
                            renameText = book.title
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Yeniden Adlandır")
                    }

                    Button(
                        onClick = {
                            deleteBookTarget = book
                            actionBook = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kitabı Sil")
                    }'''
new = '''                    if (!BundledGuide.isGuide(book.uri)) {
                        Button(
                            onClick = {
                                renameBookTarget = book
                                renameText = book.title
                                actionBook = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Yeniden Adlandır")
                        }

                        Button(
                            onClick = {
                                deleteBookTarget = book
                                actionBook = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kitabı Sil")
                        }
                    }'''
if old not in s:
    raise SystemExit('rename/delete button anchor not found')
s = s.replace(old, new, 1)

MAIN.write_text(s)

with zipfile.ZipFile(ASSET, 'r') as z:
    names = z.namelist()
    if names[0] != 'mimetype':
        raise SystemExit('mimetype must be first entry')
    if z.read('mimetype') != b'application/epub+zip':
        raise SystemExit('bad mimetype')
    if 'META-INF/container.xml' not in names or 'OEBPS/content.opf' not in names:
        raise SystemExit('incomplete epub')

print(f'Created {ASSET} ({ASSET.stat().st_size} bytes) with {len(chapters)} chapters')
