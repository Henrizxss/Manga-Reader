package com.pessoal.leitordemanga;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.LayoutTransition;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "MangaReader";
    private static final String PREFS = "manga_reader";
    private static final String ASSET_FOLDER = "manga";
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
    private volatile PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private ImageView pageImage;
    private TextView pageCounter;
    private TextView zoomLabel;
    private TextView compactPageCounter;
    private SeekBar pageSeekBar;
    private LinearLayout readerControlsContent;
    private Button readerControlsToggle;
    private ImageButton fullscreenButton;
    private Bitmap pageBitmap;
    private String currentFileName;
    private List<String> libraryFiles = new ArrayList<>();
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
        selectedSurfaceColor = getColor(R.color.reader_surface_burgundy);
        accentColor = getColor(R.color.reader_accent);
        brightAccentColor = getColor(R.color.reader_accent_bright);
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
        avatar.setContentDescription("Foto de perfil de Café Pilão");
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(roundBackground(accentColor, dp(40)));
        avatar.setClipToOutline(true);
        toolbar.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.VERTICAL);
        profile.setPadding(dp(12), 0, dp(4), 0);
        TextView username = makeText("Café Pilão", 16, primaryTextColor, true);
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
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.bottomMargin = dp(16);
        root.addView(settingsPanel, settingsParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(false);
        scrollView.setFillViewport(true);
        LinearLayout chapters = new LinearLayout(this);
        chapters.setOrientation(LinearLayout.VERTICAL);
        chapters.setPadding(0, dp(2), 0, dp(20));
        scrollView.addView(chapters);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        List<String> files;
        try {
            files = listPdfAssets();
            libraryFiles = files;
        } catch (IOException exception) {
            Log.e(TAG, "Não foi possível listar os PDFs da biblioteca.", exception);
            showMessage("Não foi possível carregar os PDFs: " + exception.getMessage());
            setContentView(root);
            return;
        }

        if (files.isEmpty()) {
            TextView empty = makeText(
                    "Nenhum PDF na biblioteca ainda.\n\nAdicione os volumes em:\n"
                            + "app/src/main/assets/manga\n\n"
                            + "Dica: use 001 - Volume 1.pdf, 002 - Volume 2.pdf…",
                    13, secondaryTextColor, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(40), dp(28), dp(40));
            chapters.addView(empty);
        } else {
            for (int index = 0; index < files.size(); index++) {
                String fileName = files.get(index);
                chapters.addView(makeChapterCard(fileName));
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

    private View makeChapterCard(String fileName) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(13), dp(12), dp(13));
        card.setMinimumHeight(dp(60));
        android.content.res.ColorStateList ripple = new android.content.res.ColorStateList(
                new int[][]{new int[0]}, new int[]{Color.argb(22, 255, 255, 255)});
        card.setBackground(new RippleDrawable(
                ripple, null, roundBackground(primaryTextColor, dp(10))));

        int totalPages = preferences.getInt(totalKey(fileName), 0);
        int lastPage = preferences.getInt(pageKey(fileName), 0);
        BitSet pages = getReadPages(fileName);
        String status;
        if (totalPages > 0 && pages.cardinality() >= totalPages) {
            status = "CONCLUÍDO";
        } else if (lastPage > 0 || !pages.isEmpty()) {
            status = totalPages > 0
                    ? "PÁG. " + (lastPage + 1) + " / " + totalPages
                    : pages.cardinality() + " LIDA(S)";
        } else {
            status = "NÃO INICIADO";
        }

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText(displayName(fileName), 15, primaryTextColor, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView statusLabel = makeText(status, 9, secondaryTextColor, true);
        statusLabel.setLetterSpacing(0.04f);
        statusLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        statusLabel.setPadding(dp(8), 0, 0, 0);
        titleRow.addView(statusLabel);
        card.addView(titleRow);

        if (totalPages > 0) {
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
            view.animate()
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(75)
                    .withEndAction(() -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                        openPdf(fileName);
                    })
                    .start();
        });
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void openPdf(String fileName) {
        closeCurrentDocument();
        try {
            File pdfFile = copyAssetToPrivateStorage(fileName);
            fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfRenderer = new PdfRenderer(fileDescriptor);
            currentFileName = fileName;
            int pageCount = pdfRenderer.getPageCount();
            if (pageCount <= 0) {
                closeCurrentDocument();
                showMessage("Este PDF não contém páginas.");
                showLibrary();
                return;
            }

            preferences.edit().putInt(totalKey(fileName), pageCount).apply();
            readPages = getReadPages(fileName);
            if (readPages.length() > pageCount) {
                readPages.clear(pageCount, readPages.length());
            }
            currentPage = Math.max(0, Math.min(pageCount - 1, preferences.getInt(pageKey(fileName), 0)));
            showReader();
            setCurrentPage(currentPage);
        } catch (IOException exception) {
            closeCurrentDocument();
            Log.e(TAG, "Não foi possível abrir " + fileName, exception);
            showMessage("Não foi possível abrir o PDF: " + exception.getMessage());
            showLibrary();
        }
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

        TextView chapterTitle = makeText(displayName(currentFileName), 14, primaryTextColor, true);
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
        if (currentFileName == null || libraryFiles.isEmpty()) {
            return;
        }
        int currentIndex = libraryFiles.indexOf(currentFileName);
        int targetIndex = currentIndex + offset;
        if (targetIndex < 0 || targetIndex >= libraryFiles.size()) {
            showMessage(offset > 0 ? "Você chegou ao último mangá." : "Este já é o primeiro mangá.");
            return;
        }
        openPdf(libraryFiles.get(targetIndex));
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
            showMessage("O progresso de " + displayName(fileName) + " estava inválido e foi reiniciado.");
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
}
