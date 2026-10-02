package zw.hatggurd.abe;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/*
 * Hatggurd District ABE - Android host.
 *
 * A browser can save a file and open a print dialog by itself. An Android WebView cannot:
 * it ignores the download attribute on a link, and window.print() does nothing at all there.
 * So this activity hands the page three things to call, under the name ABE_NATIVE:
 *
 *     ABE_NATIVE.saveFile(name, base64, mime)   - writes the file into Downloads
 *     ABE_NATIVE.openFile(name, base64, mime)   - opens it in the phone's own reader
 *     ABE_NATIVE.printHtml(html, orientation, title) - sends a sheet to Android's printer
 *
 * It also lets the app's sounds start without the person having to tap first.
 * Nothing here needs the internet: every file is already inside the app.
 */
public class MainActivity extends BridgeActivity {

    /** held while Android pulls the pages out of the print WebView */
    private WebView printView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView web = this.getBridge().getWebView();

        // the app's little sounds are allowed to play
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);

        web.addJavascriptInterface(new AbeNative(), "ABE_NATIVE");
    }

    public class AbeNative {

        /** Write a file the app carries into the phone's Downloads folder. */
        @JavascriptInterface
        public String saveFile(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 and newer: no permission needed, the file goes through MediaStore
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) { toast("Could not save " + name); return ""; }
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(bytes);
                    out.close();
                    v.clear();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                } else {
                    // Android 9 and older
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    FileOutputStream out = new FileOutputStream(new File(dir, name));
                    out.write(bytes);
                    out.close();
                }
                toast("Saved to Downloads: " + name);
                return "Downloads";
            } catch (Exception e) {
                toast("Could not save " + name);
                return "";
            }
        }

        /** Open a file the app carries, in whatever app the phone uses for that kind of file. */
        @JavascriptInterface
        public void openFile(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream out = new FileOutputStream(f);
                out.write(bytes);
                out.close();

                Uri uri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", f);
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(uri, mime);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(view, name);
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
            } catch (Exception e) {
                toast("No app on this phone can open " + name);
            }
        }

        /** Send a finished sheet to Android's own print service (which also saves as PDF). */
        @JavascriptInterface
        public void printHtml(final String html, final String orientation, final String title) {
            runOnUiThread(new Runnable() {
                public void run() { doPrint(html, orientation, title); }
            });
        }

        private void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
            });
        }
    }

    private void doPrint(String html, String orientation, String title) {
        final boolean landscape = "landscape".equals(orientation);
        final String jobName = (title == null || title.length() == 0) ? "Hatggurd District ABE" : title;

        final WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(false);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                    PrintAttributes.MediaSize size = landscape
                            ? PrintAttributes.MediaSize.ISO_A4.asLandscape()
                            : PrintAttributes.MediaSize.ISO_A4.asPortrait();
                    PrintAttributes attrs = new PrintAttributes.Builder()
                            .setMediaSize(size)
                            .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                            .build();
                    PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
                    pm.print(jobName, adapter, attrs);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "This phone has no printing service", Toast.LENGTH_LONG).show();
                }
                // printView stays held: Android still needs this WebView while it builds the pages
            }
        });
        printView = wv;
        try {
            // handed over as base64 so no accent, dash or curly quote can be mangled on the way
            String payload = Base64.encodeToString(html.getBytes("UTF-8"), Base64.NO_WRAP);
            wv.loadData(payload, "text/html; charset=utf-8", "base64");
        } catch (Exception e) {
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }
}

