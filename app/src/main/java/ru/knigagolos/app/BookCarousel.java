package ru.knigagolos.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

// Keep the browsing order stable until the listener opens the library again.
final class BookCarousel extends RecyclerView.Adapter<BookCarousel.Card> {
  private final List<Book> books;
  private final Runnable repeat;

  BookCarousel(List<Book> books, Runnable repeat) {
    this.books = books;
    this.repeat = repeat;
  }

  Book at(int index) {
    return index >= 0 && index < books.size() ? books.get(index) : null;
  }

  int indexOf(String id) {
    for (int i = 0; i < books.size(); i++) if (books.get(i).id.equals(id)) return i;
    return 0;
  }

  @Override
  public int getItemCount() {
    return books.size();
  }

  static class Card extends RecyclerView.ViewHolder {
    final TextView author, title;

    Card(LinearLayout page, TextView author, TextView title) {
      super(page);
      this.author = author;
      this.title = title;
    }
  }

  @Override
  public Card onCreateViewHolder(ViewGroup parent, int viewType) {
    Context c = parent.getContext();
    LinearLayout page = new LinearLayout(c);
    page.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
    page.setOrientation(LinearLayout.VERTICAL);
    page.setGravity(Gravity.CENTER_HORIZONTAL);
    int padding = (int) (12 * c.getResources().getDisplayMetrics().density);
    page.setPadding(padding, padding, padding, padding);
    TextView author = new TextView(c);
    author.setTextColor(Color.BLACK);
    author.setTextSize(30);
    author.setGravity(Gravity.CENTER);
    author.setMaxLines(2);
    page.addView(author, -1, -2);
    TextView title = new TextView(c);
    title.setTextColor(Color.BLACK);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    title.setMaxLines(5);
    title.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (right - left != oldRight - oldLeft) fitWords(title);
        });
    page.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
    page.setOnClickListener(v -> repeat.run());
    return new Card(page, author, title);
  }

  @Override
  public void onBindViewHolder(Card card, int position) {
    Book book = books.get(position);
    card.author.setText(book.author);
    card.title.setMaxLines(!book.title.contains(" ") && book.title.length() <= 18 ? 1 : 5);
    card.title.setText(book.title);
    fitWords(card.title);
  }

  private static void fitWords(TextView title) {
    int width = title.getWidth() - title.getCompoundPaddingLeft() - title.getCompoundPaddingRight();
    if (width <= 0) return;
    var metrics = title.getResources().getDisplayMetrics();
    int max = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 116, metrics));
    int min = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24, metrics));
    TextPaint paint = new TextPaint(title.getPaint());
    paint.setTextSize(max);
    float longest = 1;
    for (String word : title.getText().toString().split("\\s+"))
      longest = Math.max(longest, paint.measureText(word));
    int cap = Math.max(min + 1, (int) (max * Math.min(1f, (width - 2) / longest)));
    title.setAutoSizeTextTypeUniformWithConfiguration(min, cap, 1, TypedValue.COMPLEX_UNIT_PX);
  }
}
