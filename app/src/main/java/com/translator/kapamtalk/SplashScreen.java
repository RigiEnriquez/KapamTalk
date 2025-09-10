package com.translator.kapamtalk;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Build;
import android.view.Window;
import android.graphics.Color;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;


public class SplashScreen extends AppCompatActivity {

    private static int LOADING_SCREEN = 3000;

    //variables
    Animation topAnim, botAnim;
    ImageView image;
    TextView logo, slogan, slogan2, slogan3, slogan4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
        setContentView(R.layout.splashscreen);

        topAnim = AnimationUtils.loadAnimation(this,R.anim.top_animation);
        botAnim = AnimationUtils.loadAnimation(this,R.anim.bot_animation);

        image = findViewById(R.id.imageView);
        logo = findViewById(R.id.textView);
        slogan = findViewById(R.id.textView2);
        slogan2 = findViewById(R.id.textView3);
        slogan3 = findViewById(R.id.textView4);
        slogan4 = findViewById(R.id.textView5);

        image.setAnimation(topAnim);
        logo.setAnimation(botAnim);
        slogan.setAnimation(botAnim);
        slogan2.setAnimation(botAnim);
        slogan3.setAnimation(botAnim);
        slogan4.setAnimation(botAnim);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent run = new Intent(SplashScreen.this,Dashboard.class);
                startActivity(run);
                finish();
            }
        },LOADING_SCREEN);


        }
    }