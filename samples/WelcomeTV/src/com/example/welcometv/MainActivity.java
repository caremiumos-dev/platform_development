package com.example.welcometv;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.util.Random;

public class MainActivity extends Activity {
    private RelativeLayout mainLayout;
    private TextView textView;
    private MediaPlayer welcomePlayer;
    private MediaPlayer cnyPlayer;
    private Random random = new Random();
    private String[] welcomeEmojis = {"🌸", "🌼", "🌻", "🎈", "🎊", "✨"};
    private String[] cnyEmojis = {"🐎", "💰", "🧧", "🧨", "🏮", "🧧"};
    private boolean isWelcomePage = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainLayout = findViewById(R.id.main_layout);
        textView = findViewById(R.id.welcome_text);

        welcomePlayer = MediaPlayer.create(this, R.raw.welcome_music);
        welcomePlayer.setLooping(true);
        
        cnyPlayer = MediaPlayer.create(this, R.raw.cny_music);
        cnyPlayer.setLooping(true);

        welcomePlayer.start();

        animateText();
        startLoop();
        
        for (int i = 0; i < 20; i++) {
            addFloatingEmoji();
        }
    }

    private void startLoop() {
        textView.postDelayed(new Runnable() {
            @Override
            public void run() {
                togglePage();
                textView.postDelayed(this, 10000);
            }
        }, 10000);
    }

    private void togglePage() {
        isWelcomePage = !isWelcomePage;
        if (isWelcomePage) {
            textView.setText("Welcome 2026 TV Summit");
            textView.setTextColor(0xFFFFFFFF); // White
            mainLayout.setBackgroundColor(0xFF000033); // Dark Blue
            
            if (cnyPlayer.isPlaying()) cnyPlayer.pause();
            welcomePlayer.start();
        } else {
            textView.setText("Happy Chinese New Year");
            textView.setTextColor(0xFFFFD700); // Gold
            mainLayout.setBackgroundColor(0xFF8B0000); // Dark Red
            
            if (welcomePlayer.isPlaying()) welcomePlayer.pause();
            cnyPlayer.start();
        }
    }

    private void animateText() {
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(
                textView,
                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f),
                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f)
        );
        pulse.setDuration(1000);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.start();
    }

    private void addFloatingEmoji() {
        final TextView emojiView = new TextView(this);
        
        // Dynamic emoji selection based on current page
        Runnable emojiUpdater = new Runnable() {
            @Override
            public void run() {
                String[] currentEmojis = isWelcomePage ? welcomeEmojis : cnyEmojis;
                emojiView.setText(currentEmojis[random.nextInt(currentEmojis.length)]);
                emojiView.postDelayed(this, 1000 + random.nextInt(2000));
            }
        };
        emojiView.post(emojiUpdater);
        emojiView.setTextSize(40 + random.nextInt(40));

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mainLayout.addView(emojiView, params);

        int startX = random.nextInt(1000);
        int startY = 1000 + random.nextInt(500);
        int endY = -500;

        emojiView.setX(startX);
        emojiView.setY(startY);

        ObjectAnimator moveUp = ObjectAnimator.ofFloat(emojiView, "translationY", startY, endY);
        moveUp.setDuration(5000 + random.nextInt(5000));
        moveUp.setRepeatCount(ObjectAnimator.INFINITE);
        moveUp.setInterpolator(new AccelerateDecelerateInterpolator());
        
        // Random horizontal drift
        ObjectAnimator drift = ObjectAnimator.ofFloat(emojiView, "translationX", 
            startX - 100 + random.nextInt(200), startX - 100 + random.nextInt(200));
        drift.setDuration(3000 + random.nextInt(2000));
        drift.setRepeatCount(ObjectAnimator.INFINITE);
        drift.setRepeatMode(ObjectAnimator.REVERSE);

        moveUp.start();
        drift.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (welcomePlayer != null) {
            welcomePlayer.release();
            welcomePlayer = null;
        }
        if (cnyPlayer != null) {
            cnyPlayer.release();
            cnyPlayer = null;
        }
    }
}
