package nl.xservices.plugins;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.CountDownTimer;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Toast extends CordovaPlugin {

  private static final String ACTION_SHOW_EVENT = "show";
  private static final String ACTION_HIDE_EVENT = "hide";

  private static final int GRAVITY_TOP = Gravity.TOP|Gravity.CENTER_HORIZONTAL;
  private static final int GRAVITY_CENTER = Gravity.CENTER_VERTICAL|Gravity.CENTER_HORIZONTAL;
  private static final int GRAVITY_BOTTOM = Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;

  private static final int BASE_TOP_BOTTOM_OFFSET = 20;

  private android.widget.Toast mostRecentToast;
  private ViewGroup viewGroup;

  private static final boolean IS_AT_LEAST_LOLLIPOP = Build.VERSION.SDK_INT >= 21;
  private static final boolean IS_AT_LEAST_ANDROID_11 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;

  // note that webView.isPaused() is not Xwalk compatible, so tracking it poor-man style
  private boolean isPaused;

  private String currentMessage;
  private JSONObject currentData;
  private static CountDownTimer _timer;

  @Override
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
    if (ACTION_HIDE_EVENT.equals(action)) {
      returnTapEvent("hide", currentMessage, currentData, callbackContext);
      hide();
      callbackContext.success();
      return true;

    } else if (ACTION_SHOW_EVENT.equals(action)) {
      if (this.isPaused) {
        return true;
      }

      final JSONObject options = args.getJSONObject(0);
      final String duration = options.getString("duration");
      final String position = options.getString("position");
      final int addPixelsY = options.has("addPixelsY") ? options.getInt("addPixelsY") : 0;
      final JSONObject data = options.has("data") ? options.getJSONObject("data") : null;
      JSONObject styling = options.optJSONObject("styling");
      final String msg = options.getString("message");
      currentMessage = msg;
      currentData = data;

      String _msg = msg;
      if(styling != null){
        final String textColor = styling.optString("textColor", "#000000");
        _msg = "<font color='"+textColor+"' ><b>" + _msg + "</b></font>";
      }
      final String html = _msg;

      cordova.getActivity().runOnUiThread(new Runnable() {
        public void run() {
          int hideAfterMs;
          if ("short".equalsIgnoreCase(duration)) {
            hideAfterMs = 2000;
          } else if ("long".equalsIgnoreCase(duration)) {
            hideAfterMs = 4000;
          } else {
            // assuming a number of ms
            hideAfterMs = Integer.parseInt(duration);
          }

          Spanned message;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // FROM_HTML_MODE_LEGACY is the behaviour that was used for versions below android N
            // we are using this flag to give a consistent behaviour
            message = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY);
          } else {
            message = Html.fromHtml(html);
          }

          final android.widget.Toast toast = android.widget.Toast.makeText(
                  IS_AT_LEAST_LOLLIPOP ? cordova.getActivity().getWindow().getContext() : cordova.getActivity().getApplicationContext(),
                  message,
                  "short".equalsIgnoreCase(duration) ? android.widget.Toast.LENGTH_SHORT : android.widget.Toast.LENGTH_LONG
          );

          if ("top".equals(position)) {
            toast.setGravity(GRAVITY_TOP, 0, BASE_TOP_BOTTOM_OFFSET + addPixelsY);
          } else  if ("bottom".equals(position)) {
            toast.setGravity(GRAVITY_BOTTOM, 0, BASE_TOP_BOTTOM_OFFSET - addPixelsY);
          } else if ("center".equals(position)) {
            toast.setGravity(GRAVITY_CENTER, 0, addPixelsY);
          } else {
            callbackContext.error("invalid position. valid options are 'top', 'center' and 'bottom'");
            return;
          }

          // On Android >= 5 you can no longer rely on the 'toast.getView().setOnTouchListener',
          // so created something funky that compares the Toast position to the tap coordinates.
          if (IS_AT_LEAST_LOLLIPOP) {
            if (IS_AT_LEAST_ANDROID_11) {
              toast.addCallback(new android.widget.Toast.Callback(){
                public void onToastShown() {}
                public void onToastHidden() {
                  returnTapEvent("hide", msg, data, callbackContext);
                }
              });
            } else {
              getViewGroup().setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                  if (motionEvent.getAction() != MotionEvent.ACTION_DOWN) {
                    return false;
                  }
                  if (mostRecentToast == null || !mostRecentToast.getView().isShown()) {
                    getViewGroup().setOnTouchListener(null);
                    return false;
                  }

                  float w = mostRecentToast.getView().getWidth();
                  float startX = (view.getWidth() / 2) - (w / 2);
                  float endX = (view.getWidth() / 2) + (w / 2);

                  float startY;
                  float endY;

                  float g = mostRecentToast.getGravity();
                  float y = mostRecentToast.getYOffset();
                  float h = mostRecentToast.getView().getHeight();

                  if (g == GRAVITY_BOTTOM) {
                    startY = view.getHeight() - y - h;
                    endY = view.getHeight() - y;
                  } else if (g == GRAVITY_CENTER) {
                    startY = (view.getHeight() / 2) + y - (h / 2);
                    endY = (view.getHeight() / 2) + y + (h / 2);
                  } else {
                    // top
                    startY = y;
                    endY = y + h;
                  }

                  float tapX = motionEvent.getX();
                  float tapY = motionEvent.getY();

                  final boolean tapped = tapX >= startX && tapX <= endX &&
                          tapY >= startY && tapY <= endY;

                  return tapped && returnTapEvent("touch", msg, data, callbackContext);
                }
              });
            }

          } else {
            toast.getView().setOnTouchListener(new View.OnTouchListener() {
              @Override
              public boolean onTouch(View view, MotionEvent motionEvent) {
                return motionEvent.getAction() == MotionEvent.ACTION_DOWN && returnTapEvent("touch", msg, data, callbackContext);
              }
            });
          }
          // trigger show every 2500 ms for as long as the requested duration
          _timer = new CountDownTimer(hideAfterMs, 2500) {
            public void onTick(long millisUntilFinished) {
              // see https://github.com/EddyVerbruggen/Toast-PhoneGap-Plugin/issues/116
              // and https://github.com/EddyVerbruggen/Toast-PhoneGap-Plugin/issues/120
//              if (!IS_AT_LEAST_PIE) {
//                toast.show();
//              }
            }
            public void onFinish() {
              returnTapEvent("hide", msg, data, callbackContext);
              toast.cancel();
            }
          }.start();

          mostRecentToast = toast;
          toast.show();

          PluginResult pr = new PluginResult(PluginResult.Status.OK);
          pr.setKeepCallback(true);
          callbackContext.sendPluginResult(pr);
        }
      });

      return true;
    } else {
      callbackContext.error("toast." + action + " is not a supported function. Did you mean '" + ACTION_SHOW_EVENT + "'?");
      return false;
    }
  }


  private void hide() {
    if (mostRecentToast != null) {
      mostRecentToast.cancel();
      getViewGroup().setOnTouchListener(null);
    }
    if (_timer != null) {
      _timer.cancel();
    }
  }

  private boolean returnTapEvent(String eventName, String message, JSONObject data, CallbackContext callbackContext) {
    final JSONObject json = new JSONObject();
    try {
      json.put("event", eventName);
      json.put("message", message);
      json.put("data", data);
    } catch (JSONException e) {
      e.printStackTrace();
    }
    callbackContext.success(json);
    return true;
  }

  // lazy init and caching
  private ViewGroup getViewGroup() {
    if (viewGroup == null) {
      viewGroup = (ViewGroup) ((ViewGroup) cordova.getActivity().findViewById(android.R.id.content)).getChildAt(0);
    }
    return viewGroup;
  }

  @Override
  public void onPause(boolean multitasking) {
    hide();
    this.isPaused = true;
  }

  @Override
  public void onResume(boolean multitasking) {
    this.isPaused = false;
  }
}

