package com.pessoal.leitordemanga;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.LayoutTransition;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MangaReader";
    private static final String PREFS = "manga_reader";
    private static final String ASSET_FOLDER = "manga";
    private static final String PICKED_FILES_PREF = "picked_drive_files";
    private static final int PICK_PDFS_REQUEST_CODE = 7142;
    private static final int PICK_PROFILE_PHOTO_REQUEST_CODE = 7143;
    private static final String DEFAULT_PROFILE_NAME = "Café Pilão";
    private static final String PROFILE_NAME_PREF = "profile_name";
    private static final String PROFILE_PHOTO_URI_PREF = "profile_photo_uri";
    private static final String PROFILE_ACCENT_COLOR_PREF = "profile_accent_color";
    private static final float MAX_ZOOM = 5f;
    private static final int UI_ANIMATION_DURATION_MS = 220;

    private SharedPreferences preferences;
    private int pageBackground;
    private int surfaceColor;
    private int raisedSurfaceColor;
    private int selectedSurfaceColor;
    private int accentColor;
    private int brightAccentColor;
    private int primaryTextColor;
    private int secondaryTextColor;
    private int dividerColor;
    private int progressTrackColor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pageRenderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private volatile PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private ImageView pageImage;
    private TextView pageCounter;
    private TextView zoomLabel;
    private TextView compactPageCounter;
    private SeekBar pageSeekBar;
    private TextView driveStatusText;
    private Button drivePickerButton;
    private LinearLayout readerControlsContent;
    private Button readerControlsToggle;
    private ImageButton fullscreenButton;
    private Bitmap pageBitmap;
    private String currentFileName;
    private MangaVolume currentVolume;
    private List<MangaVolume> libraryFiles = new ArrayList<>();
    private Map<String, MangaVolume> pickedVolumes = new HashMap<>();
    private final Set<String> downloadingVolumeKeys = new HashSet<>();
    private BitSet readPages = new BitSet();
    private int currentPage;
    private boolean swipeDownToNext;
    private boolean keepScreenOn;
    private boolean readerControlsExpanded;
    private boolean fullscreenEnabled;
    private boolean isUpdatingSeekBar;
    private boolean isScaling;
    private volatile int pageRenderGeneration;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private float zoomScale = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadDesignTokens();
        swipeDownToNext = preferences.getBoolean("swipe_down_to_next", false);
        keepScreenOn = preferences.getBoolean("keep_screen_on", true);
        readerControlsExpanded = preferences.getBoolean("reader_controls_expanded", true);
        showLibrary();
    }

    private void loadDesignTokens() {
        pageBackground = getColor(R.color.reader_background);
        surfaceColor = getColor(R.color.reader_surface);
        raisedSurfaceColor = getColor(R.color.reader_surface_raised);
        boolean customAccent = preferences.contains(PROFILE_ACCENT_COLOR_PREF);
        accentColor = preferences.getInt(
                PROFILE_ACCENT_COLOR_PREF, getColor(R.color.reader_accent));
        selectedSurfaceColor = customAccent
                ? blendColor(surfaceColor, accentColor, 0.22f)
                : getColor(R.color.reader_surface_burgundy);
        brightAccentColor = customAccent
                ? blendColor(accentColor, Color.WHITE, 0.18f)
                : getColor(R.color.reader_accent_bright);
        primaryTextColor = getColor(R.color.reader_text_primary);
        secondaryTextColor = getColor(R.color.reader_text_secondary);
        dividerColor = getColor(R.color.reader_divider);
        progressTrackColor = getColor(R.color.reader_progress_track);
    }

    @Override
    public void onBackPressed() {
        if (fullscreenEnabled) {
            setFullscreenEnabled(false);
            return;
        }
        if (pdfRenderer != null) {
            closeCurrentDocument();
            showLibrary();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pageRenderGeneration++;
        closeCurrentDocument();
        pageRenderExecutor.shutdownNow();
        fileExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (pdfRenderer != null) {
            showReader();
            setCurrentPage(currentPage);
        } else {
            showLibrary();
        }
    }

    private void showLibrary() {
        fullscreenButton = null;
        setFullscreenEnabled(false);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(pageBackground);
        getWindow().setNavigationBarColor(pageBackground);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageBackground);
        root.setLayoutTransition(new LayoutTransition());
        root.setPadding(dp(20), 0, dp(20), 0);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(2), dp(12), dp(2), dp(12));
        toolbar.setBackgroundColor(Color.TRANSPARENT);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.avatar_kaneki);
        String profileName = preferences.getString(PROFILE_NAME_PREF, DEFAULT_PROFILE_NAME);
        String profilePhotoUri = preferences.getString(PROFILE_PHOTO_URI_PREF, null);
        if (profilePhotoUri != null && !profilePhotoUri.isEmpty()) {
            try {
                avatar.setImageURI(Uri.parse(profilePhotoUri));
                if (avatar.getDrawable() == null) {
                    avatar.setImageResource(R.drawable.avatar_kaneki);
                    Log.w(TAG, "A foto de perfil salva não pôde ser carregada.");
                }
            } catch (SecurityException | IllegalArgumentException exception) {
                Log.w(TAG, "Não foi possível acessar a foto de perfil salva.", exception);
                avatar.setImageResource(R.drawable.avatar_kaneki);
            }
        }
        avatar.setContentDescription("Foto de perfil de " + profileName);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(roundBackground(accentColor, dp(40)));
        avatar.setClipToOutline(true);
        toolbar.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.VERTICAL);
        profile.setPadding(dp(12), 0, dp(4), 0);
        TextView username = makeText(profileName, 16, primaryTextColor, true);
        TextView profileCaption = makeText("COLEÇÃO PESSOAL", 10, secondaryTextColor, true);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(3);
        profile.addView(username);
        profile.addView(profileCaption, captionParams);
        toolbar.addView(profile, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolbarParams.setMargins(0, dp(6), 0, dp(18));
        root.addView(toolbar, toolbarParams);

        TextView sectionTitle = makeText("BIBLIOTECA", 10, brightAccentColor, true);
        sectionTitle.setLetterSpacing(0.12f);
        sectionTitle.setPadding(0, 0, 0, dp(5));
        root.addView(sectionTitle);
        TextView title = makeText("Seus mangás", 23, primaryTextColor, true);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);
        TextView subtitle = makeText(
                "Sua coleção, pronta para continuar.",
                12, secondaryTextColor, false);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

        LinearLayout settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(14), dp(4), dp(14), dp(4));
        settingsPanel.setBackground(roundBackground(surfaceColor, dp(16)));

        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout directionText = new LinearLayout(this);
        directionText.setOrientation(LinearLayout.VERTICAL);
        TextView directionLabel = makeText("Gesto para avançar", 13, primaryTextColor, true);
        TextView directionDescription = makeText(
                swipeDownToNext
                        ? "Arraste para baixo para avançar"
                        : "Arraste da direita para a esquerda",
                11, secondaryTextColor, false);
        directionText.addView(directionLabel);
        LinearLayout.LayoutParams directionDescriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        directionDescriptionParams.topMargin = dp(2);
        directionText.addView(directionDescription, directionDescriptionParams);
        directionRow.addView(directionText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch directionSwitch = makePreferenceSwitch(swipeDownToNext);
        directionSwitch.setContentDescription(swipeDownToNext
                ? "Arrastar para baixo para avançar páginas"
                : "Arrastar da direita para a esquerda para avançar páginas");
        directionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            swipeDownToNext = isChecked;
            directionSwitch.setContentDescription(isChecked
                    ? "Arrastar para baixo para avançar páginas"
                    : "Arrastar da direita para a esquerda para avançar páginas");
            directionDescription.setText(isChecked
                    ? "Arraste para baixo para avançar"
                    : "Arraste da direita para a esquerda");
            preferences.edit().putBoolean("swipe_down_to_next", isChecked).apply();
        });
        directionRow.addView(directionSwitch);
        directionRow.setMinimumHeight(dp(56));
        settingsPanel.addView(directionRow);

        View settingsDivider = new View(this);
        settingsDivider.setBackgroundColor(dividerColor);
        settingsPanel.addView(settingsDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout awakeRow = new LinearLayout(this);
        awakeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView awakeLabel = makeText("Manter a tela ligada", 13, primaryTextColor, true);
        TextView awakeDescription = makeText(
                "Enquanto você estiver lendo", 11, secondaryTextColor, false);
        LinearLayout awakeText = new LinearLayout(this);
        awakeText.setOrientation(LinearLayout.VERTICAL);
        awakeText.addView(awakeLabel);
        LinearLayout.LayoutParams awakeDescriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        awakeDescriptionParams.topMargin = dp(2);
        awakeText.addView(awakeDescription, awakeDescriptionParams);
        awakeRow.addView(awakeText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch awakeSwitch = makePreferenceSwitch(keepScreenOn);
        awakeSwitch.setContentDescription("Manter a tela ligada durante a leitura");
        awakeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            keepScreenOn = isChecked;
            preferences.edit().putBoolean("keep_screen_on", isChecked).apply();
        });
        awakeRow.addView(awakeSwitch);
        awakeRow.setMinimumHeight(dp(56));
        settingsPanel.addView(awakeRow);

        Button customizeButton = makeButton("Personalizar perfil e cor");
        customizeButton.setTextSize(12);
        LinearLayout.LayoutParams customizeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        customizeParams.topMargin = dp(6);
        customizeParams.bottomMargin = dp(6);
        settingsPanel.addView(customizeButton, customizeParams);
        customizeButton.setOnClickListener(view -> showProfileCustomizationMenu());

        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.bottomMargin = dp(16);
        root.addView(settingsPanel, settingsParams);

        LinearLayout driveRow = new LinearLayout(this);
        driveRow.setGravity(Gravity.CENTER_VERTICAL);
        driveStatusText = makeText(
                "Escolha PDFs no Google Drive ou no aparelho",
                10, secondaryTextColor, false);
        driveStatusText.setSingleLine(true);
        driveStatusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        driveRow.addView(driveStatusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        drivePickerButton = makeButton("Selecionar PDFs");
        drivePickerButton.setTextSize(11);
        drivePickerButton.setOnClickListener(view -> openPdfPicker());
        driveRow.addView(drivePickerButton);
        LinearLayout.LayoutParams driveRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        driveRowParams.bottomMargin = dp(10);
        root.addView(driveRow, driveRowParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);
        LinearLayout chapters = new LinearLayout(this);
        chapters.setOrientation(LinearLayout.VERTICAL);
        chapters.setPadding(0, dp(2), 0, dp(20));
        scrollView.addView(chapters);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        List<MangaVolume> files;
        try {
            files = listLibraryVolumes();
            libraryFiles = files;
        } catch (IOException exception) {
            Log.e(TAG, "Não foi possível carregar a biblioteca.", exception);
            showMessage("Não foi possível carregar a biblioteca: " + exception.getMessage());
            setContentView(root);
            return;
        }

        if (files.isEmpty()) {
            TextView empty = makeText(
                    "Nenhum volume adicionado.\n\nToque em Adicionar PDFs e escolha os mangás no Google Drive.",
                    13, secondaryTextColor, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(40), dp(28), dp(40));
            chapters.addView(empty);
        } else {
            for (int index = 0; index < files.size(); index++) {
                MangaVolume volume = files.get(index);
                chapters.addView(makeChapterCard(volume));
                if (index < files.size() - 1) {
                    View divider = new View(this);
                    divider.setBackgroundColor(dividerColor);
                    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                    dividerParams.setMargins(dp(12), 0, dp(12), 0);
                    chapters.addView(divider, dividerParams);
                }
            }
        }

        setContentView(root);
        animateEntrance(toolbar, 0);
        animateEntrance(title, 45);
        for (int index = 0; index < chapters.getChildCount(); index++) {
            animateEntrance(chapters.getChildAt(index), 70 + index * 35L);
        }
    }

    private List<MangaVolume> listLibraryVolumes() throws IOException {
        Map<String, MangaVolume> volumesByKey = new HashMap<>();
        for (String name : listPdfAssets()) {
            MangaVolume volume = new MangaVolume(name, name, null, false, -1, true);
            volumesByKey.put(volume.key, volume);
        }

        JSONArray savedFiles = readPickedFiles();
        for (int index = 0; index < savedFiles.length(); index++) {
            JSONObject file = savedFiles.optJSONObject(index);
            if (file == null) {
                continue;
            }
            String uriText = file.optString("uri", "");
            String name = file.optString("name", "");
            if (uriText.isEmpty() || name.isEmpty()) {
                continue;
            }
            long size = file.optLong("size", -1);
            String key = pickedFileKey(uriText);
            MangaVolume volume = new MangaVolume(key, name, uriText, true, size, false);
            volumesByKey.put(volume.key, volume);
        }
        for (MangaVolume volume : pickedVolumes.values()) {
            volumesByKey.put(volume.key, volume);
        }

        List<MangaVolume> volumes = new ArrayList<>(volumesByKey.values());
        Collator collator = Collator.getInstance(Locale.ROOT);
        Collections.sort(volumes, (first, second) -> {
            int seriesOrder = Integer.compare(
                    seriesOrder(first.name), seriesOrder(second.name));
            if (seriesOrder != 0) {
                return seriesOrder;
            }
            int volumeOrder = Integer.compare(
                    volumeNumber(first.name), volumeNumber(second.name));
            return volumeOrder != 0 ? volumeOrder : collator.compare(first.name, second.name);
        });
        return volumes;
    }

    private List<String> listPdfAssets() throws IOException {
        String[] assetNames = getAssets().list(ASSET_FOLDER);
        List<String> files = new ArrayList<>();
        if (assetNames != null) {
            for (String name : assetNames) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    files.add(name);
                }
            }
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        Collections.sort(files, (first, second) -> {
            int seriesOrder = Integer.compare(seriesOrder(first), seriesOrder(second));
            if (seriesOrder != 0) {
                return seriesOrder;
            }
            int volumeOrder = Integer.compare(volumeNumber(first), volumeNumber(second));
            return volumeOrder != 0 ? volumeOrder : collator.compare(first, second);
        });
        return files;
    }

    private JSONArray readPickedFiles() {
        String savedFiles = preferences.getString(PICKED_FILES_PREF, "[]");
        try {
            return new JSONArray(savedFiles);
        } catch (JSONException exception) {
            Log.e(TAG, "A lista local de PDFs selecionados está inválida.", exception);
            preferences.edit().remove(PICKED_FILES_PREF).apply();
            showMessage("A lista local de PDFs estava inválida e foi limpa.");
            return new JSONArray();
        }
    }

    private void savePickedFiles(JSONArray files) {
        preferences.edit().putString(PICKED_FILES_PREF, files.toString()).apply();
    }

    private String pickedFileKey(String uriText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uriText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("picked:");
            for (byte value : digest) {
                key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível neste Android.", exception);
        }
    }

    private File getDownloadFile(String key) {
        String safeName = key.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(new File(getFilesDir(), ASSET_FOLDER), safeName + ".pdf");
    }

    private int seriesOrder(String fileName) {
        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("tokyo ghoul - re")) {
            return 1;
        }
        if (normalizedName.contains("tokyo ghoul") && normalizedName.contains("volume")) {
            return 0;
        }
        return 2;
    }

    private int volumeNumber(String fileName) {
        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        String marker = seriesOrder(fileName) == 1 ? "v" : "volume";
        int markerIndex = normalizedName.indexOf(marker, "tokyo ghoul".length());
        if (markerIndex < 0) {
            return Integer.MAX_VALUE;
        }

        int cursor = markerIndex + marker.length();
        while (cursor < normalizedName.length() && Character.isWhitespace(normalizedName.charAt(cursor))) {
            cursor++;
        }
        int numberStart = cursor;
        while (cursor < normalizedName.length() && Character.isDigit(normalizedName.charAt(cursor))) {
            cursor++;
        }
        if (numberStart == cursor) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(normalizedName.substring(numberStart, cursor));
        } catch (NumberFormatException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private View makeChapterCard(MangaVolume volume) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(13), dp(12), dp(13));
        card.setMinimumHeight(dp(60));
        android.content.res.ColorStateList ripple = new android.content.res.ColorStateList(
                new int[][]{new int[0]}, new int[]{Color.argb(22, 255, 255, 255)});
        card.setBackground(new RippleDrawable(
                ripple, null, roundBackground(primaryTextColor, dp(10))));

        boolean downloaded = volume.isBundled || isVolumeDownloaded(volume);
        boolean downloading = downloadingVolumeKeys.contains(volume.key);
        int totalPages = preferences.getInt(totalKey(volume.key), 0);
        int lastPage = preferences.getInt(pageKey(volume.key), 0);
        BitSet pages = getReadPages(volume.key);
        String status;
        if (downloading) {
            status = "BAIXANDO";
        } else if (!downloaded) {
            status = "SELECIONADO · PRONTO PARA BAIXAR";
        } else if (totalPages > 0 && pages.cardinality() >= totalPages) {
            status = "CONCLUÍDO";
        } else if (lastPage > 0 || !pages.isEmpty()) {
            status = totalPages > 0
                    ? "OFFLINE · PÁG. " + (lastPage + 1) + " / " + totalPages
                    : "OFFLINE · " + pages.cardinality() + " LIDA(S)";
        } else {
            status = downloaded ? "DISPONÍVEL OFFLINE" : "PRONTO PARA BAIXAR";
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText(displayName(volume.name), 15, primaryTextColor, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(titleRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView statusLabel = makeText(status, 9, secondaryTextColor, true);
        statusLabel.setLetterSpacing(0.04f);
        statusRow.addView(statusLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!volume.isBundled) {
            Button downloadButton = makeButton(
                    downloading ? "Baixando…" : downloaded ? "Remover" : "Baixar");
            downloadButton.setTextSize(10);
            downloadButton.setMinHeight(dp(34));
            downloadButton.setMinimumHeight(dp(34));
            downloadButton.setPadding(dp(12), 0, dp(12), 0);
            downloadButton.setEnabled(!downloading);
            if (downloaded) {
                downloadButton.setTextColor(brightAccentColor);
            }
            downloadButton.setContentDescription(downloading
                    ? "Baixando " + displayName(volume.name)
                    : downloaded
                    ? "Remover " + displayName(volume.name)
                            + " deste aparelho, sem apagar o original"
                    : "Baixar " + displayName(volume.name) + " para este aparelho");
            downloadButton.setOnClickListener(view -> {
                if (isVolumeDownloaded(volume)) {
                    confirmRemoveDownloadedVolume(volume);
                } else {
                    downloadSelectedVolume(volume, false);
                }
            });
            statusRow.addView(downloadButton);
        }
        LinearLayout.LayoutParams statusRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusRowParams.topMargin = dp(5);
        card.addView(statusRow, statusRowParams);

        if (downloading) {
            ProgressBar downloadProgressBar = new ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            downloadProgressBar.setIndeterminate(true);
            downloadProgressBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(brightAccentColor));
            downloadProgressBar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(progressTrackColor));
            LinearLayout.LayoutParams downloadProgressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
            downloadProgressParams.topMargin = dp(10);
            card.addView(downloadProgressBar, downloadProgressParams);
        }

        if (downloaded && totalPages > 0) {
            ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(Math.min(100, pages.cardinality() * 100 / totalPages));
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(brightAccentColor));
            progressBar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(progressTrackColor));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            progressParams.topMargin = dp(10);
            card.addView(progressBar, progressParams);
        }

        card.setOnClickListener(view -> {
            if (downloadingVolumeKeys.contains(volume.key)) {
                return;
            }
            view.animate()
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(75)
                    .withEndAction(() -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                        openVolume(volume);
                    })
                    .start();
        });
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void confirmRemoveDownloadedVolume(MangaVolume volume) {
        new AlertDialog.Builder(this)
                .setTitle("Remover download?")
                .setMessage(displayName(volume.name)
                        + " será removido deste app e aparelho. O arquivo original na origem "
                        + "(por exemplo, no Google Drive) não será apagado. "
                        + "O progresso de leitura será mantido.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Remover", (dialog, which) -> {
                    File cachedFile = getDownloadFile(volume.key);
                    if (cachedFile.exists() && !cachedFile.delete()) {
                        showMessage("Não foi possível remover o arquivo local.");
                        return;
                    }
                    removeSelectedVolume(volume);
                    showMessage("Mangá removido do app. O arquivo original não foi alterado.");
                    showLibrary();
                })
                .show();
    }

    private void removeSelectedVolume(MangaVolume volume) {
        JSONArray savedFiles = readPickedFiles();
        JSONArray retainedFiles = new JSONArray();
        for (int index = 0; index < savedFiles.length(); index++) {
            JSONObject file = savedFiles.optJSONObject(index);
            if (file == null || !volume.sourceUri.equals(file.optString("uri", ""))) {
                if (file != null) {
                    retainedFiles.put(file);
                }
            }
        }
        savePickedFiles(retainedFiles);
        pickedVolumes.remove(volume.key);
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(volume.sourceUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.w(TAG, "Não foi possível liberar a permissão salva para "
                    + volume.name, exception);
        }
    }

    private boolean isVolumeDownloaded(MangaVolume volume) {
        return volume.isBundled
                || (volume.sourceUri != null && getDownloadFile(volume.key).isFile());
    }

    private void openVolume(MangaVolume volume) {
        if (volume.isBundled) {
            try {
                openPdfFile(volume, copyAssetToPrivateStorage(volume.name));
            } catch (IOException exception) {
                Log.e(TAG, "Não foi possível preparar " + volume.name, exception);
                showMessage("Não foi possível abrir o PDF: " + exception.getMessage());
            }
            return;
        }

        File cachedFile = getDownloadFile(volume.key);
        if (cachedFile.isFile()) {
            openPdfFile(volume, cachedFile);
        } else if (volume.availableFromSource) {
            downloadSelectedVolume(volume, true);
        } else {
            showMessage("Este volume não está baixado. Selecione-o novamente na origem.");
        }
    }

    private void openPdfFile(MangaVolume volume, File pdfFile) {
        closeCurrentDocument();
        try {
            fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            currentVolume = volume;
            currentFileName = volume.key;
            int pageCount = pdfRenderer.getPageCount();
            if (pageCount <= 0) {
                closeCurrentDocument();
                showMessage("Este PDF não contém páginas.");
                showLibrary();
                return;
            }

            preferences.edit().putInt(totalKey(volume.key), pageCount).apply();
            readPages = getReadPages(volume.key);
            if (readPages.length() > pageCount) {
                readPages.clear(pageCount, readPages.length());
            }
            currentPage = Math.max(0, Math.min(
                    pageCount - 1, preferences.getInt(pageKey(volume.key), 0)));
            showReader();
            setCurrentPage(currentPage);
        } catch (IOException exception) {
            closeCurrentDocument();
            Log.e(TAG, "Não foi possível abrir " + volume.name, exception);
            showMessage("Não foi possível abrir o PDF: " + exception.getMessage());
            showLibrary();
        }
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_PDFS_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException exception) {
            Log.e(TAG, "Não há um seletor de arquivos disponível no aparelho.", exception);
            showMessage("Não foi possível abrir o seletor de arquivos deste aparelho.");
        }
    }

    private void showProfileCustomizationMenu() {
        String[] options = {
                "Alterar nome",
                "Alterar foto de perfil",
                "Alterar cor de destaque",
                "Voltar ao padrão"
        };
        new AlertDialog.Builder(this)
                .setTitle("Personalização")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEditProfileNameDialog();
                    } else if (which == 1) {
                        openProfilePhotoPicker();
                    } else if (which == 2) {
                        showAccentColorDialog();
                    } else if (which == 3) {
                        confirmRestoreProfileDefaults();
                    }
                })
                .show();
    }

    private void showEditProfileNameDialog() {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameInput.setText(preferences.getString(PROFILE_NAME_PREF, DEFAULT_PROFILE_NAME));
        nameInput.setSelection(nameInput.getText().length());
        nameInput.setPadding(dp(14), dp(10), dp(14), dp(10));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nome exibido")
                .setView(nameInput)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameInput.setError("Digite um nome.");
                        return;
                    }
                    preferences.edit().putString(PROFILE_NAME_PREF, name).apply();
                    dialog.dismiss();
                    showLibrary();
                }));
        dialog.show();
    }

    private void openProfilePhotoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_PROFILE_PHOTO_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException exception) {
            Log.e(TAG, "Não há um seletor de imagens disponível no aparelho.", exception);
            showMessage("Não foi possível abrir o seletor de imagens deste aparelho.");
        }
    }

    private void showAccentColorDialog() {
        int initialColor = preferences.getInt(
                PROFILE_ACCENT_COLOR_PREF, getColor(R.color.reader_accent));
        int[] components = {
                Color.red(initialColor),
                Color.green(initialColor),
                Color.blue(initialColor)
        };
        String[] channelNames = {"Vermelho", "Verde", "Azul"};
        TextView preview = makeText("", 14, Color.WHITE, true);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(4), dp(22), dp(8));
        content.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        for (int channel = 0; channel < components.length; channel++) {
            final int channelIndex = channel;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = makeText(channelNames[channel], 11, primaryTextColor, false);
            row.addView(label, new LinearLayout.LayoutParams(dp(66), dp(44)));
            SeekBar seekBar = new SeekBar(this);
            seekBar.setMax(255);
            seekBar.setProgress(components[channel]);
            seekBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(brightAccentColor));
            seekBar.setThumbTintList(
                    android.content.res.ColorStateList.valueOf(brightAccentColor));
            row.addView(seekBar, new LinearLayout.LayoutParams(
                    0, dp(44), 1));
            content.addView(row);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    components[channelIndex] = progress;
                    updateAccentColorPreview(preview, components);
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                }
            });
        }
        updateAccentColorPreview(preview, components);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cor de destaque")
                .setView(content)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar", (dialogInterface, which) -> {
                    int chosenColor = Color.rgb(
                            components[0], components[1], components[2]);
                    preferences.edit()
                            .putInt(PROFILE_ACCENT_COLOR_PREF, chosenColor)
                            .apply();
                    loadDesignTokens();
                    showLibrary();
                })
                .create();
        dialog.show();
    }

    private void updateAccentColorPreview(TextView preview, int[] components) {
        int color = Color.rgb(components[0], components[1], components[2]);
        preview.setText(String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        preview.setBackground(roundBackground(color, dp(10)));
    }

    private void confirmRestoreProfileDefaults() {
        new AlertDialog.Builder(this)
                .setTitle("Voltar ao padrão?")
                .setMessage("O nome, a foto de perfil e a cor voltarão aos valores originais. "
                        + "Seu progresso e suas preferências de leitura serão mantidos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> {
                    String photoUri = preferences.getString(PROFILE_PHOTO_URI_PREF, null);
                    if (photoUri != null) {
                        releaseProfilePhotoPermission(photoUri);
                    }
                    preferences.edit()
                            .remove(PROFILE_NAME_PREF)
                            .remove(PROFILE_PHOTO_URI_PREF)
                            .remove(PROFILE_ACCENT_COLOR_PREF)
                            .apply();
                    loadDesignTokens();
                    showLibrary();
                })
                .show();
    }

    private void releaseProfilePhotoPermission(String uriText) {
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uriText), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.w(TAG, "Não foi possível liberar o acesso persistente à foto de perfil.", exception);
        }
    }

    private int blendColor(int from, int to, float ratio) {
        float inverseRatio = 1f - ratio;
        return Color.rgb(
                Math.round(Color.red(from) * inverseRatio + Color.red(to) * ratio),
                Math.round(Color.green(from) * inverseRatio + Color.green(to) * ratio),
                Math.round(Color.blue(from) * inverseRatio + Color.blue(to) * ratio));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PROFILE_PHOTO_REQUEST_CODE) {
            handleProfilePhotoResult(resultCode, data);
            return;
        }
        if (requestCode != PICK_PDFS_REQUEST_CODE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        List<Uri> selectedUris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri uri = clipData.getItemAt(index).getUri();
                if (uri != null) {
                    selectedUris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            selectedUris.add(data.getData());
        }
        if (selectedUris.isEmpty()) {
            showMessage("O seletor não retornou arquivos. Tente selecionar os PDFs novamente.");
            return;
        }

        JSONArray savedFiles = readPickedFiles();
        Set<String> knownUris = new HashSet<>();
        for (int index = 0; index < savedFiles.length(); index++) {
            JSONObject file = savedFiles.optJSONObject(index);
            if (file != null) {
                knownUris.add(file.optString("uri", ""));
            }
        }
        int addedCount = 0;
        ContentResolver resolver = getContentResolver();
        for (Uri uri : selectedUris) {
            if (!"content".equals(uri.getScheme())) {
                continue;
            }
            try {
                int resultFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    resolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (SecurityException | IllegalArgumentException exception) {
                Log.w(TAG, "O provedor não permitiu guardar acesso permanente a " + uri, exception);
            }

            String uriText = uri.toString();
            if (knownUris.contains(uriText)) {
                continue;
            }
            try {
                String name = queryDisplayName(uri);
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    showMessage("Um arquivo selecionado não parece ser um PDF: "
                            + (name == null ? "arquivo sem nome" : name));
                    continue;
                }
                JSONObject file = new JSONObject();
                file.put("uri", uriText);
                file.put("name", name);
                file.put("size", queryFileSize(uri));
                savedFiles.put(file);
                knownUris.add(uriText);
                addedCount++;
            } catch (IOException | JSONException exception) {
                Log.e(TAG, "Não foi possível adicionar o PDF selecionado " + uri, exception);
                showMessage("Não foi possível adicionar um PDF: " + exception.getMessage());
            }
        }
        savePickedFiles(savedFiles);
        loadPickedLibrary(savedFiles, addedCount);
    }

    private void handleProfilePhotoResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri selectedPhoto = data.getData();
        int grantedFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (grantedFlags == 0) {
            showMessage("O seletor não concedeu acesso permanente à imagem. Escolha outra foto.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(selectedPhoto, grantedFlags);
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.e(TAG, "Não foi possível salvar o acesso à foto de perfil.", exception);
            showMessage("Não foi possível guardar acesso a esta foto. "
                    + "Escolha uma imagem de Arquivos ou Galeria.");
            return;
        }

        String previousPhoto = preferences.getString(PROFILE_PHOTO_URI_PREF, null);
        preferences.edit()
                .putString(PROFILE_PHOTO_URI_PREF, selectedPhoto.toString())
                .apply();
        if (previousPhoto != null && !previousPhoto.equals(selectedPhoto.toString())) {
            releaseProfilePhotoPermission(previousPhoto);
        }
        showLibrary();
    }

    private String queryDisplayName(Uri uri) throws IOException {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0) {
                    return cursor.getString(nameColumn);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Não foi possível ler o nome do arquivo selecionado.", exception);
        }
        return null;
    }

    private long queryFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    return cursor.getLong(sizeColumn);
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "O provedor não informou o tamanho de " + uri, exception);
        }
        return -1;
    }

    private void loadPickedLibrary(JSONArray files, int newlyAddedCount) {
        Map<String, MangaVolume> loadedVolumes = new HashMap<>();
        int selectedCount = 0;
        for (int index = 0; index < files.length(); index++) {
            JSONObject file = files.optJSONObject(index);
            if (file == null) {
                continue;
            }
            String uriText = file.optString("uri", "");
            String name = file.optString("name", "");
            if (uriText.isEmpty() || name.isEmpty()) {
                continue;
            }
            String key = pickedFileKey(uriText);
            MangaVolume volume = new MangaVolume(
                    key, name, uriText, true, file.optLong("size", -1), false);
            loadedVolumes.put(key, volume);
            selectedCount++;
        }
        pickedVolumes = loadedVolumes;
        showLibrary();
        if (driveStatusText != null) {
            driveStatusText.setText(selectedCount == 1
                    ? "1 PDF selecionado"
                    : selectedCount + " PDFs selecionados");
        }
        if (newlyAddedCount > 0) {
            showMessage(newlyAddedCount + (newlyAddedCount == 1
                    ? " PDF adicionado à biblioteca."
                    : " PDFs adicionados à biblioteca."));
        } else {
            showMessage("Os PDFs selecionados já estão na biblioteca.");
        }
    }

    private void downloadSelectedVolume(MangaVolume volume, boolean openAfterDownload) {
        if (!downloadingVolumeKeys.add(volume.key)) {
            return;
        }
        if (!openAfterDownload) {
            showLibrary();
        }
        setPickerUiBusy(true, "Baixando " + displayName(volume.name) + "…");
        File destination = getDownloadFile(volume.key);
        fileExecutor.execute(() -> {
            long[] lastUpdate = {0};
            try {
                copySelectedPdf(volume, destination, (copiedBytes, totalBytes) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] < 400
                            && (totalBytes < 0 || copiedBytes < totalBytes)) {
                        return;
                    }
                    lastUpdate[0] = now;
                    String progress = totalBytes > 0
                            ? "Baixando " + displayName(volume.name) + " · "
                                    + (copiedBytes * 100 / totalBytes) + "%"
                            : "Baixando " + displayName(volume.name) + " · "
                                    + formatFileSize(copiedBytes);
                    mainHandler.post(() -> setPickerUiBusy(true, progress));
                });
                mainHandler.post(() -> {
                    downloadingVolumeKeys.remove(volume.key);
                    setPickerUiBusy(false, displayName(volume.name) + " disponível offline");
                    if (openAfterDownload) {
                        openPdfFile(volume, destination);
                    } else {
                        showLibrary();
                        setPickerUiBusy(false,
                                displayName(volume.name) + " disponível offline");
                        if (driveStatusText != null) {
                            driveStatusText.setText(displayName(volume.name)
                                    + " baixado para leitura offline");
                        }
                        showMessage(displayName(volume.name)
                                + " foi baixado para este aparelho.");
                    }
                });
            } catch (IOException exception) {
                Log.e(TAG, "Não foi possível copiar o PDF selecionado " + volume.name, exception);
                mainHandler.post(() -> {
                    downloadingVolumeKeys.remove(volume.key);
                    if (!openAfterDownload) {
                        showLibrary();
                    }
                    showPickerError(
                            "Não foi possível baixar o PDF. Selecione-o novamente e tente outra vez: "
                                    + exception.getMessage());
                });
            }
        });
    }

    private void copySelectedPdf(
            MangaVolume volume,
            File destination,
            CopyProgress progress
    ) throws IOException {
        if (volume.sourceUri == null) {
            throw new IOException("O arquivo selecionado não tem uma origem disponível.");
        }
        Uri sourceUri = Uri.parse(volume.sourceUri);
        File directory = destination.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Não foi possível criar a pasta local dos PDFs.");
        }
        File temporaryFile = new File(directory, destination.getName() + ".download");
        boolean completed = false;
        IOException copyFailure = null;
        try {
            long totalBytes = volume.size;
            long copiedBytes = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = getContentResolver().openInputStream(sourceUri)) {
                if (input == null) {
                    throw new IOException("A origem não abriu o PDF.");
                }
                try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        copiedBytes += bytesRead;
                        progress.onProgress(copiedBytes, totalBytes);
                    }
                }
            }
            if (temporaryFile.length() == 0) {
                throw new IOException("O PDF recebido está vazio.");
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Não foi possível substituir a cópia local do PDF.");
            }
            if (!temporaryFile.renameTo(destination)) {
                throw new IOException("Não foi possível guardar o PDF no aparelho.");
            }
            completed = true;
        } catch (IOException exception) {
            copyFailure = exception;
            throw exception;
        } finally {
            if (!completed && temporaryFile.exists() && !temporaryFile.delete()) {
                IOException cleanupFailure = new IOException(
                        "O download falhou e o arquivo temporário não pôde ser removido.");
                if (copyFailure != null) {
                    copyFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    private void setPickerUiBusy(boolean busy, String status) {
        if (driveStatusText != null) {
            driveStatusText.setText(status);
        }
        if (drivePickerButton != null) {
            drivePickerButton.setEnabled(!busy);
            drivePickerButton.setAlpha(busy ? 0.55f : 1f);
        }
    }

    private void showPickerError(String message) {
        setPickerUiBusy(false, "Não foi possível acessar o PDF");
        showMessage(message);
    }

    private interface CopyProgress {
        void onProgress(long copiedBytes, long totalBytes);
    }

    private File copyAssetToPrivateStorage(String fileName) throws IOException {
        File directory = new File(getFilesDir(), ASSET_FOLDER);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta local dos PDFs.");
        }

        File destination = new File(directory, fileName);
        try {
            long appUpdateTime = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            if (destination.isFile() && destination.length() > 0
                    && destination.lastModified() >= appUpdateTime) {
                return destination;
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            throw new IOException("Não foi possível verificar a versão instalada do app.", exception);
        }

        File temporaryFile = new File(directory, fileName + ".tmp");
        try (InputStream input = getAssets().open(ASSET_FOLDER + "/" + fileName);
             FileOutputStream output = new FileOutputStream(temporaryFile)) {
            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Não foi possível atualizar a cópia local do PDF.");
        }
        if (!temporaryFile.renameTo(destination)) {
            throw new IOException("Não foi possível salvar a cópia local do PDF.");
        }
        return destination;
    }

    private void showReader() {
        if (keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().setStatusBarColor(pageBackground);
        getWindow().setNavigationBarColor(pageBackground);
        getWindow().getDecorView().setSystemUiVisibility(0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageBackground);
        root.setLayoutTransition(new LayoutTransition());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(2), dp(12), dp(2));
        ImageButton backButton = makeIconButton(R.drawable.ic_arrow_back, "Voltar à biblioteca");
        backButton.setOnClickListener(view -> onBackPressed());
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView chapterTitle = makeText(
                displayName(currentVolume.name), 14, primaryTextColor, true);
        chapterTitle.setMaxLines(1);
        chapterTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chapterTitle.setPadding(dp(10), 0, dp(6), 0);
        toolbar.addView(chapterTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fullscreenButton = makeIconButton(R.drawable.ic_fullscreen, "Ativar tela cheia");
        fullscreenButton.setContentDescription("Ativar tela cheia");
        fullscreenButton.setOnClickListener(view -> setFullscreenEnabled(!fullscreenEnabled));
        toolbar.addView(fullscreenButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(toolbar);

        pageImage = new ImageView(this);
        pageImage.setBackgroundColor(pageBackground);
        pageImage.setScaleType(ImageView.ScaleType.MATRIX);
        pageImage.setAdjustViewBounds(false);
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                changeZoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
            }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                boolean next = event.getX() < pageImage.getWidth() / 2f;
                movePage(next ? 1 : -1);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (zoomScale > 1.05f) {
                    resetZoom();
                } else {
                    changeZoom(2.5f, event.getX(), event.getY());
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
                if (!isScaling && zoomScale > 1.01f) {
                    panImage(-distanceX, -distanceY);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
                if (zoomScale <= 1.01f && first != null && last != null) {
                    float deltaX = last.getX() - first.getX();
                    float deltaY = last.getY() - first.getY();
                    if (swipeDownToNext
                            && Math.abs(deltaY) > dp(48)
                            && Math.abs(deltaY) > Math.abs(deltaX)) {
                        movePage(deltaY > 0 ? 1 : -1);
                        return true;
                    }
                    if (!swipeDownToNext
                            && Math.abs(deltaX) > dp(48)
                            && Math.abs(deltaX) > Math.abs(deltaY)) {
                        movePage(deltaX < 0 ? 1 : -1);
                        return true;
                    }
                }
                return false;
            }
        });
        pageImage.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                gestureDetector.onTouchEvent(event);
                return true;
            }
        });
        root.addView(pageImage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setPadding(dp(10), dp(5), dp(10), dp(7));
        controlsPanel.setLayoutTransition(new LayoutTransition());
        GradientDrawable panelBackground = roundBackground(surfaceColor, dp(18));
        controlsPanel.setBackground(panelBackground);

        LinearLayout controlsHeader = new LinearLayout(this);
        controlsHeader.setGravity(Gravity.CENTER_VERTICAL);
        readerControlsToggle = makeButton(readerControlsExpanded
                ? "Ocultar controles"
                : "Mostrar controles");
        readerControlsToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                readerControlsExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more,
                0, 0, 0);
        readerControlsToggle.setCompoundDrawablePadding(dp(7));
        readerControlsToggle.setBackground(roundBackground(surfaceColor, dp(12)));
        readerControlsToggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        readerControlsToggle.setTextSize(12);
        readerControlsToggle.setContentDescription(readerControlsExpanded
                ? "Recolher controles de leitura"
                : "Expandir controles de leitura");
        readerControlsToggle.setOnClickListener(view -> toggleReaderControls());
        controlsHeader.addView(readerControlsToggle, new LinearLayout.LayoutParams(
                0, dp(44), 1));
        compactPageCounter = makeText("", 12, secondaryTextColor, true);
        compactPageCounter.setGravity(Gravity.CENTER);
        compactPageCounter.setPadding(dp(8), 0, dp(8), 0);
        controlsHeader.addView(compactPageCounter);
        controlsPanel.addView(controlsHeader);

        readerControlsContent = new LinearLayout(this);
        readerControlsContent.setOrientation(LinearLayout.VERTICAL);

        TextView pageGroupLabel = makeGroupLabel("PÁGINAS");
        pageGroupLabel.setPadding(dp(10), dp(4), dp(10), 0);
        readerControlsContent.addView(pageGroupLabel);

        pageCounter = makeText("", 12, primaryTextColor, false);
        pageCounter.setGravity(Gravity.CENTER);
        pageCounter.setPadding(dp(12), dp(1), dp(12), 0);
        readerControlsContent.addView(pageCounter);

        pageSeekBar = new SeekBar(this);
        pageSeekBar.setPadding(dp(12), 0, dp(12), 0);
        pageSeekBar.setMax(Math.max(0, pdfRenderer.getPageCount() - 1));
        pageSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(brightAccentColor));
        pageSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(brightAccentColor));
        pageSeekBar.setContentDescription("Progresso da página");
        pageSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !isUpdatingSeekBar) {
                    setCurrentPage(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        readerControlsContent.addView(pageSeekBar);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(4), 0, dp(4), 0);
        ImageButton previousButton = makeIconButton(R.drawable.ic_arrow_left, "Página anterior");
        previousButton.setOnClickListener(view -> movePage(-1));
        Button jumpButton = makeButton("Ir à página");
        jumpButton.setTextSize(11);
        jumpButton.setOnClickListener(view -> showJumpToPageDialog());
        ImageButton nextButton = makeIconButton(R.drawable.ic_arrow_right, "Página seguinte");
        nextButton.setOnClickListener(view -> movePage(1));
        controls.addView(previousButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        LinearLayout.LayoutParams jumpParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        jumpParams.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(jumpButton, jumpParams);
        controls.addView(nextButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        readerControlsContent.addView(controls);

        TextView zoomGroupLabel = makeGroupLabel("ZOOM");
        zoomGroupLabel.setPadding(dp(10), dp(8), dp(10), 0);
        readerControlsContent.addView(zoomGroupLabel);
        LinearLayout zoomControls = new LinearLayout(this);
        zoomControls.setGravity(Gravity.CENTER);
        zoomControls.setPadding(dp(4), 0, dp(4), dp(3));
        ImageButton zoomOutButton = makeIconButton(R.drawable.ic_zoom_out, "Diminuir zoom");
        zoomOutButton.setOnClickListener(view -> changeZoom(1f / 1.25f,
                pageImage.getWidth() / 2f, pageImage.getHeight() / 2f));
        zoomLabel = makeText("100%", 12, primaryTextColor, true);
        zoomLabel.setGravity(Gravity.CENTER);
        ImageButton zoomInButton = makeIconButton(R.drawable.ic_zoom_in, "Aumentar zoom");
        zoomInButton.setOnClickListener(view -> changeZoom(1.25f,
                pageImage.getWidth() / 2f, pageImage.getHeight() / 2f));
        Button resetZoomButton = makeButton("Ajustar");
        resetZoomButton.setTextSize(11);
        resetZoomButton.setOnClickListener(view -> resetZoom());
        zoomControls.addView(zoomOutButton, new LinearLayout.LayoutParams(dp(42), dp(40)));
        zoomControls.addView(zoomLabel, new LinearLayout.LayoutParams(dp(50), dp(40)));
        zoomControls.addView(zoomInButton, new LinearLayout.LayoutParams(dp(42), dp(40)));
        LinearLayout.LayoutParams resetZoomParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        resetZoomParams.setMargins(dp(8), 0, 0, 0);
        zoomControls.addView(resetZoomButton, resetZoomParams);
        readerControlsContent.addView(zoomControls);

        TextView volumeGroupLabel = makeGroupLabel("VOLUMES");
        volumeGroupLabel.setPadding(dp(10), dp(5), dp(10), 0);
        readerControlsContent.addView(volumeGroupLabel);
        LinearLayout volumeControls = new LinearLayout(this);
        volumeControls.setGravity(Gravity.CENTER);
        volumeControls.setPadding(dp(4), 0, dp(4), dp(3));
        Button previousVolumeButton = makeButton("Anterior");
        previousVolumeButton.setTextSize(11);
        previousVolumeButton.setOnClickListener(view -> moveVolume(-1));
        Button nextVolumeButton = makeButton("Próximo");
        nextVolumeButton.setTextSize(11);
        nextVolumeButton.setOnClickListener(view -> moveVolume(1));
        volumeControls.addView(previousVolumeButton,
                new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams nextVolumeParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        nextVolumeParams.setMargins(dp(8), 0, 0, 0);
        volumeControls.addView(nextVolumeButton,
                nextVolumeParams);
        readerControlsContent.addView(volumeControls);

        Button resetButton = makeButton("Zerar progresso deste volume");
        resetButton.setTextSize(10);
        resetButton.setTextColor(secondaryTextColor);
        resetButton.setBackgroundColor(Color.TRANSPARENT);
        resetButton.setOnClickListener(view -> confirmResetProgress());
        resetButton.setPadding(dp(12), 0, dp(12), 0);
        readerControlsContent.addView(resetButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        readerControlsContent.setVisibility(readerControlsExpanded ? View.VISIBLE : View.GONE);
        controlsPanel.addView(readerControlsContent);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.setMargins(dp(8), dp(4), dp(8), dp(8));
        root.addView(controlsPanel, panelParams);
        controlsPanel.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset = fullscreenEnabled
                    ? Math.max(insets.getSystemWindowInsetBottom(), dp(24))
                    : 0;
            view.setPadding(dp(10), dp(5), dp(10), dp(7) + bottomInset);
            return insets;
        });

        setContentView(root);
        updateFullscreenSystemUi();
        animateEntrance(toolbar, 0);
        animateEntrance(pageImage, 35);
        animateEntrance(controlsPanel, 70);
    }

    private void toggleReaderControls() {
        readerControlsExpanded = !readerControlsExpanded;
        preferences.edit()
                .putBoolean("reader_controls_expanded", readerControlsExpanded)
                .apply();
        readerControlsToggle.setText(readerControlsExpanded
                ? "Ocultar controles"
                : "Mostrar controles");
        readerControlsToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                readerControlsExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more,
                0, 0, 0);
        readerControlsToggle.setContentDescription(readerControlsExpanded
                ? "Recolher controles de leitura"
                : "Expandir controles de leitura");
        readerControlsContent.animate().cancel();
        if (readerControlsExpanded) {
            readerControlsContent.setVisibility(View.VISIBLE);
            readerControlsContent.setAlpha(0f);
            readerControlsContent.setTranslationY(dp(12));
            readerControlsContent.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(UI_ANIMATION_DURATION_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            readerControlsContent.animate()
                    .alpha(0f)
                    .translationY(dp(12))
                    .setDuration(UI_ANIMATION_DURATION_MS - 40)
                    .withEndAction(() -> {
                        readerControlsContent.setVisibility(View.GONE);
                        readerControlsContent.setAlpha(1f);
                        readerControlsContent.setTranslationY(0f);
                    })
                    .start();
        }
    }

    private void setFullscreenEnabled(boolean enabled) {
        fullscreenEnabled = enabled;
        updateFullscreenSystemUi();
        getWindow().getDecorView().requestApplyInsets();
    }

    private void updateFullscreenSystemUi() {
        if (getWindow() == null) {
            return;
        }
        int flags = 0;
        if (fullscreenEnabled && pdfRenderer != null) {
            flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (fullscreenButton != null) {
            fullscreenButton.setImageResource(fullscreenEnabled
                    ? R.drawable.ic_fullscreen_exit
                    : R.drawable.ic_fullscreen);
            fullscreenButton.setContentDescription(
                    fullscreenEnabled ? "Sair da tela cheia" : "Ativar tela cheia");
        }
    }

    private void animateEntrance(View view, long delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp(10));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(UI_ANIMATION_DURATION_MS + 40)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void setCurrentPage(int pageIndex) {
        if (pdfRenderer == null || pageImage == null) {
            return;
        }
        currentPage = Math.max(0, Math.min(pdfRenderer.getPageCount() - 1, pageIndex));
        readPages.set(currentPage);
        preferences.edit()
                .putInt(pageKey(currentFileName), currentPage)
                .putString(readKey(currentFileName),
                        Base64.encodeToString(readPages.toByteArray(), Base64.NO_WRAP))
                .apply();

        pageCounter.setText("Página " + (currentPage + 1) + " de " + pdfRenderer.getPageCount()
                + "  ·  " + readPages.cardinality() + " lida(s)");
        compactPageCounter.setText((currentPage + 1) + " / " + pdfRenderer.getPageCount());
        isUpdatingSeekBar = true;
        pageSeekBar.setProgress(currentPage);
        isUpdatingSeekBar = false;
        pageImage.post(this::renderCurrentPage);
    }

    private void movePage(int offset) {
        if (pdfRenderer != null) {
            int target = currentPage + offset;
            if (target >= 0 && target < pdfRenderer.getPageCount()) {
                setCurrentPage(target);
            } else if (offset > 0) {
                moveVolume(1);
            } else if (offset < 0 && currentPage == 0) {
                moveVolume(-1);
            }
        }
    }

    private void moveVolume(int offset) {
        if (currentVolume == null || libraryFiles.isEmpty()) {
            return;
        }
        int currentIndex = -1;
        for (int index = 0; index < libraryFiles.size(); index++) {
            if (libraryFiles.get(index).key.equals(currentVolume.key)) {
                currentIndex = index;
                break;
            }
        }
        int targetIndex = currentIndex + offset;
        if (currentIndex < 0 || targetIndex < 0 || targetIndex >= libraryFiles.size()) {
            showMessage(offset > 0 ? "Você chegou ao último mangá." : "Este já é o primeiro mangá.");
            return;
        }
        openVolume(libraryFiles.get(targetIndex));
    }

    private void showJumpToPageDialog() {
        EditText pageInput = new EditText(this);
        pageInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageInput.setSingleLine(true);
        pageInput.setHint("1–" + pdfRenderer.getPageCount());
        pageInput.setText(String.valueOf(currentPage + 1));
        pageInput.selectAll();
        int padding = dp(24);
        LinearLayout inputContainer = new LinearLayout(this);
        inputContainer.setPadding(padding, dp(8), padding, 0);
        inputContainer.addView(pageInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Ir para a página")
                .setView(inputContainer)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ir", (dialog, which) -> {
                    String value = pageInput.getText().toString().trim();
                    try {
                        int requestedPage = Integer.parseInt(value);
                        if (requestedPage < 1 || requestedPage > pdfRenderer.getPageCount()) {
                            showMessage("Informe uma página entre 1 e " + pdfRenderer.getPageCount() + ".");
                            return;
                        }
                        setCurrentPage(requestedPage - 1);
                    } catch (NumberFormatException exception) {
                        showMessage("Informe um número de página válido.");
                    }
                })
                .show();
    }

    private void changeZoom(float requestedFactor, float focusX, float focusY) {
        if (pageImage == null || pageBitmap == null) {
            return;
        }
        float newScale = Math.max(1f, Math.min(MAX_ZOOM, zoomScale * requestedFactor));
        float factor = newScale / zoomScale;
        if (Math.abs(factor - 1f) < 0.001f) {
            return;
        }
        Matrix matrix = new Matrix(pageImage.getImageMatrix());
        matrix.postScale(factor, factor, focusX, focusY);
        zoomScale = newScale;
        pageImage.setImageMatrix(matrix);
        constrainImageMatrix();
        if (zoomLabel != null) {
            zoomLabel.setText(Math.round(zoomScale * 100) + "%");
        }
    }

    private void panImage(float dx, float dy) {
        if (pageImage == null || pageBitmap == null) {
            return;
        }
        Matrix matrix = new Matrix(pageImage.getImageMatrix());
        matrix.postTranslate(dx, dy);
        pageImage.setImageMatrix(matrix);
        constrainImageMatrix();
    }

    private void resetZoom() {
        if (pageImage == null || pageBitmap == null) {
            return;
        }
        zoomScale = 1f;
        Matrix matrix = getFitMatrix();
        pageImage.setImageMatrix(matrix);
        constrainImageMatrix();
        if (zoomLabel != null) {
            zoomLabel.setText("100%");
        }
    }

    private Matrix getFitMatrix() {
        Matrix matrix = new Matrix();
        if (pageImage == null || pageBitmap == null
                || pageImage.getWidth() <= 0 || pageImage.getHeight() <= 0) {
            return matrix;
        }
        float scale = Math.min((float) pageImage.getWidth() / pageBitmap.getWidth(),
                (float) pageImage.getHeight() / pageBitmap.getHeight());
        float dx = (pageImage.getWidth() - pageBitmap.getWidth() * scale) / 2f;
        float dy = (pageImage.getHeight() - pageBitmap.getHeight() * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        return matrix;
    }

    private void constrainImageMatrix() {
        if (pageImage == null || pageBitmap == null) {
            return;
        }
        Matrix matrix = new Matrix(pageImage.getImageMatrix());
        float[] values = new float[9];
        matrix.getValues(values);
        float viewWidth = pageImage.getWidth();
        float viewHeight = pageImage.getHeight();
        float imageWidth = pageBitmap.getWidth() * values[Matrix.MSCALE_X];
        float imageHeight = pageBitmap.getHeight() * values[Matrix.MSCALE_Y];
        float minX = imageWidth > viewWidth ? viewWidth - imageWidth : (viewWidth - imageWidth) / 2f;
        float maxX = imageWidth > viewWidth ? 0 : minX;
        float minY = imageHeight > viewHeight ? viewHeight - imageHeight : (viewHeight - imageHeight) / 2f;
        float maxY = imageHeight > viewHeight ? 0 : minY;
        values[Matrix.MTRANS_X] = Math.max(minX, Math.min(maxX, values[Matrix.MTRANS_X]));
        values[Matrix.MTRANS_Y] = Math.max(minY, Math.min(maxY, values[Matrix.MTRANS_Y]));
        matrix.setValues(values);
        pageImage.setImageMatrix(matrix);
    }

    private void renderCurrentPage() {
        PdfRenderer renderer = pdfRenderer;
        ImageView targetImage = pageImage;
        if (renderer == null || targetImage == null
                || targetImage.getWidth() <= 0 || targetImage.getHeight() <= 0) {
            return;
        }
        int pageIndex = currentPage;
        int requestId = ++pageRenderGeneration;
        int targetWidth = targetImage.getWidth();
        int targetHeight = targetImage.getHeight();

        pageRenderExecutor.execute(() -> {
            Bitmap renderedBitmap;
            try {
                synchronized (renderer) {
                    if (renderer != pdfRenderer || requestId != pageRenderGeneration) {
                        return;
                    }
                    try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                        float scale = Math.min(
                                (float) targetWidth / page.getWidth(),
                                (float) targetHeight / page.getHeight());
                        scale = Math.min(scale * 2f, 4096f / Math.max(page.getWidth(), page.getHeight()));
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        renderedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        renderedBitmap.eraseColor(Color.WHITE);
                        page.render(renderedBitmap, new Rect(0, 0, width, height),
                                null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                }
            } catch (RuntimeException | OutOfMemoryError exception) {
                mainHandler.post(() -> {
                    if (requestId == pageRenderGeneration) {
                        Log.e(TAG, "Não foi possível renderizar a página " + (pageIndex + 1), exception);
                        showMessage("Não foi possível renderizar esta página: " + exception.getMessage());
                    }
                });
                return;
            }

            mainHandler.post(() -> {
                if (requestId != pageRenderGeneration || renderer != pdfRenderer
                        || targetImage != pageImage || isFinishing()) {
                    renderedBitmap.recycle();
                    return;
                }
                Bitmap previousBitmap = pageBitmap;
                targetImage.animate().cancel();
                targetImage.animate()
                        .alpha(0.72f)
                        .setDuration(65)
                        .withEndAction(() -> {
                            if (requestId != pageRenderGeneration || renderer != pdfRenderer
                                    || targetImage != pageImage || isFinishing()) {
                                renderedBitmap.recycle();
                                return;
                            }
                            pageBitmap = renderedBitmap;
                            targetImage.setImageBitmap(renderedBitmap);
                            zoomScale = 1f;
                            targetImage.setImageMatrix(getFitMatrix());
                            constrainImageMatrix();
                            if (zoomLabel != null) {
                                zoomLabel.setText("100%");
                            }
                            targetImage.animate()
                                    .alpha(1f)
                                    .setDuration(110)
                                    .setInterpolator(new DecelerateInterpolator())
                                    .start();
                            if (previousBitmap != null && !previousBitmap.isRecycled()) {
                                previousBitmap.recycle();
                            }
                        })
                        .start();
            });
        });
    }

    private void confirmResetProgress() {
        new AlertDialog.Builder(this)
                .setTitle("Zerar o progresso?")
                .setMessage("As páginas lidas e o ponto de retomada deste volume serão apagados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Zerar", (dialog, which) -> {
                    preferences.edit()
                            .remove(pageKey(currentFileName))
                            .remove(readKey(currentFileName))
                            .apply();
                    readPages.clear();
                    setCurrentPage(0);
                })
                .show();
    }

    private BitSet getReadPages(String fileName) {
        String encoded = preferences.getString(readKey(fileName), "");
        if (encoded.isEmpty()) {
            return new BitSet();
        }
        try {
            return BitSet.valueOf(Base64.decode(encoded, Base64.NO_WRAP));
        } catch (IllegalArgumentException exception) {
            Log.e(TAG, "O progresso salvo está corrompido para " + fileName, exception);
            showMessage("O progresso salvo deste volume estava inválido e foi reiniciado.");
            preferences.edit().remove(readKey(fileName)).apply();
            return new BitSet();
        }
    }

    private void closeCurrentDocument() {
        if (pdfRenderer != null) {
            PdfRenderer renderer = pdfRenderer;
            pdfRenderer = null;
            synchronized (renderer) {
                renderer.close();
            }
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (IOException exception) {
                Log.e(TAG, "Não foi possível fechar o arquivo PDF.", exception);
            }
            fileDescriptor = null;
        }
        if (pageBitmap != null) {
            if (!pageBitmap.isRecycled()) {
                pageBitmap.recycle();
            }
            pageBitmap = null;
        }
        pageImage = null;
        currentFileName = null;
        currentVolume = null;
    }

    private String displayName(String fileName) {
        if (fileName == null) {
            return "";
        }
        if (seriesOrder(fileName) == 1) {
            return "Tokyo Ghoul:re · Volume " + volumeNumber(fileName);
        }
        if (seriesOrder(fileName) == 0) {
            return "Tokyo Ghoul · Volume " + volumeNumber(fileName);
        }
        return fileName.substring(0, fileName.length() - 4).replace('_', ' ');
    }

    private String pageKey(String fileName) {
        return "page:" + fileName;
    }

    private String readKey(String fileName) {
        return "read:" + fileName;
    }

    private String totalKey(String fileName) {
        return "total:" + fileName;
    }

    private TextView makeText(String text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private TextView makeGroupLabel(String text) {
        TextView label = makeText(text, 9, secondaryTextColor, true);
        label.setLetterSpacing(0.1f);
        return label;
    }

    private Switch makePreferenceSwitch(boolean checked) {
        Switch preferenceSwitch = new Switch(this);
        preferenceSwitch.setChecked(checked);
        preferenceSwitch.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{brightAccentColor, secondaryTextColor}));
        preferenceSwitch.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{selectedSurfaceColor, dividerColor}));
        return preferenceSwitch;
    }

    private ImageButton makeIconButton(int iconResource, String contentDescription) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(primaryTextColor));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(contentDescription);
        android.content.res.ColorStateList rippleColor = new android.content.res.ColorStateList(
                new int[][]{new int[0]},
                new int[]{Color.argb(38, 255, 255, 255)});
        button.setBackground(new RippleDrawable(
                rippleColor,
                roundBackground(raisedSurfaceColor, dp(12)),
                roundBackground(primaryTextColor, dp(12))));
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
        return button;
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setTextColor(primaryTextColor);
        button.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = roundBackground(raisedSurfaceColor, dp(14));
        android.content.res.ColorStateList rippleColor = new android.content.res.ColorStateList(
                new int[][]{new int[0]},
                new int[]{Color.argb(38, 255, 255, 255)});
        button.setBackground(new RippleDrawable(rippleColor, background, null));
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
        return button;
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class MangaVolume {
        final String key;
        final String name;
        final String sourceUri;
        final boolean availableFromSource;
        final long size;
        final boolean isBundled;

        MangaVolume(
                String key,
                String name,
                String sourceUri,
                boolean availableFromSource,
                long size,
                boolean isBundled
        ) {
            this.key = key;
            this.name = name;
            this.sourceUri = sourceUri;
            this.availableFromSource = availableFromSource;
            this.size = size;
            this.isBundled = isBundled;
        }
    }
}
