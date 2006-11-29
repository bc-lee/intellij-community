package com.intellij.codeInsight.highlighting;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DataConstants;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.impl.injected.EditorDelegate;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.*;

public class HighlightManagerImpl extends HighlightManager implements ProjectComponent {

  private AnActionListener myAnActionListener;
  private DocumentListener myDocumentListener;

  private final Key<Map<RangeHighlighter, HighlightInfo>> HIGHLIGHT_INFO_MAP_KEY = Key.create("HIGHLIGHT_INFO_MAP_KEY");

  static class HighlightInfo {
    final Editor editor;
    final int flags;

    public HighlightInfo(Editor editor, int flags) {
      this.editor = editor;
      this.flags = flags;
    }
  }

  @NotNull
  public String getComponentName() {
    return "HighlightManager";
  }

  public void initComponent() {
  }

  public void disposeComponent() {
  }

  public void projectOpened() {
    myAnActionListener = new MyAnActionListener();
    ActionManagerEx.getInstanceEx().addAnActionListener(myAnActionListener);

    myDocumentListener = new DocumentAdapter() {
      public void documentChanged(DocumentEvent event) {
        Document document = event.getDocument();
        Editor[] editors = EditorFactory.getInstance().getEditors(document);
        for (Editor editor : editors) {
          Map<RangeHighlighter, HighlightInfo> map = getHighlightInfoMap(editor, false);
          if (map == null) return;

          ArrayList<RangeHighlighter> highlightersToRemove = new ArrayList<RangeHighlighter>();
          for (RangeHighlighter highlighter : map.keySet()) {
            HighlightInfo info = map.get(highlighter);
            if (!info.editor.getDocument().equals(document)) continue;
            if ((info.flags & HIDE_BY_TEXT_CHANGE) != 0) {
              highlightersToRemove.add(highlighter);
            }
          }

          for (RangeHighlighter highlighter : highlightersToRemove) {
            removeSegmentHighlighter(editor, highlighter);
          }
        }
      }
    };
    EditorFactory.getInstance().getEventMulticaster().addDocumentListener(myDocumentListener);
  }

  public void projectClosed() {
    ActionManagerEx.getInstanceEx().removeAnActionListener(myAnActionListener);
    EditorFactory.getInstance().getEventMulticaster().removeDocumentListener(myDocumentListener);
  }

  @Nullable
  public Map<RangeHighlighter, HighlightInfo> getHighlightInfoMap(Editor editor, boolean toCreate) {
    Map<RangeHighlighter, HighlightInfo> map = editor.getUserData(HIGHLIGHT_INFO_MAP_KEY);
    if (map == null && toCreate) {
      map = new HashMap<RangeHighlighter, HighlightInfo>();
      editor.putUserData(HIGHLIGHT_INFO_MAP_KEY, map);
    }
    return map;
  }

  public RangeHighlighter[] getHighlighters(Editor editor) {
    Map<RangeHighlighter, HighlightInfo> highlightersMap = getHighlightInfoMap(editor, false);
    if (highlightersMap == null) return new RangeHighlighter[0];
    Set<RangeHighlighter> set = new HashSet<RangeHighlighter>();
    for (Map.Entry<RangeHighlighter, HighlightInfo> entry : highlightersMap.entrySet()) {
      HighlightInfo info = entry.getValue();
      if (info.editor.equals(editor)) set.add(entry.getKey());
    }
    return set.toArray(new RangeHighlighter[set.size()]);
  }

  public RangeHighlighter addSegmentHighlighter(Editor editor, int startOffset, int endOffset, TextAttributes attributes, int flags) {
    RangeHighlighter highlighter = editor.getMarkupModel()
      .addRangeHighlighter(startOffset, endOffset, HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.EXACT_RANGE);
    HighlightInfo info = new HighlightInfo(editor instanceof EditorDelegate ? ((EditorDelegate)editor).getDelegate() : editor, flags);
    Map<RangeHighlighter, HighlightInfo> map = getHighlightInfoMap(editor, true);
    map.put(highlighter, info);
    return highlighter;
  }

  public boolean removeSegmentHighlighter(@NotNull Editor editor, @NotNull RangeHighlighter highlighter) {
    Map<RangeHighlighter, HighlightInfo> map = getHighlightInfoMap(editor, false);
    if (map == null) return false;
    HighlightInfo info = map.get(highlighter);
    if (info == null) return false;
    MarkupModel markupModel = info.editor.getMarkupModel();
    if (((MarkupModelEx)markupModel).containsHighlighter(highlighter)) {
      markupModel.removeHighlighter(highlighter);
    }
    map.remove(highlighter);
    return true;
  }

  public void addOccurrenceHighlights(@NotNull Editor editor,
                                      @NotNull PsiReference[] occurrences,
                                      @NotNull TextAttributes attributes,
                                      boolean hideByTextChange,
                                      Collection<RangeHighlighter> outHighlighters) {
    if (occurrences.length == 0) return;
    int flags = HIDE_BY_ESCAPE;
    if (hideByTextChange) {
      flags |= HIDE_BY_TEXT_CHANGE;
    }
    Color scrollmarkColor = getScrollMarkColor(attributes);

    int oldOffset = editor.getCaretModel().getOffset();
    int horizontalScrollOffset = editor.getScrollingModel().getHorizontalScrollOffset();
    int verticalScrollOffset = editor.getScrollingModel().getVerticalScrollOffset();
    for (PsiReference occurrence : occurrences) {
      PsiElement element = occurrence.getElement();
      int startOffset = element.getTextRange().getStartOffset();
      int start = startOffset + occurrence.getRangeInElement().getStartOffset();
      int end = startOffset + occurrence.getRangeInElement().getEndOffset();
      PsiFile containingFile = element.getContainingFile();
      Project project = element.getProject();
      // each reference can reside in its own injected editor
      Editor textEditor = InjectedLanguageUtil.openEditorFor(containingFile, project);
      addOccurrenceHighlight(textEditor, start, end, attributes, flags, outHighlighters, scrollmarkColor);
    }
    editor.getCaretModel().moveToOffset(oldOffset);
    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
    editor.getScrollingModel().scrollHorizontally(horizontalScrollOffset);
    editor.getScrollingModel().scrollVertically(verticalScrollOffset);
  }

  public void addElementsOccurrenceHighlights(@NotNull Editor editor,
                                              @NotNull PsiElement[] elements,
                                              @NotNull TextAttributes attributes,
                                              boolean hideByTextChange,
                                              Collection<RangeHighlighter> outHighlighters) {
    if (elements.length == 0) return;
    int flags = HIDE_BY_ESCAPE;
    if (hideByTextChange) {
      flags |= HIDE_BY_TEXT_CHANGE;
    }
    Color scrollmarkColor = getScrollMarkColor(attributes);

    for (final PsiElement element : elements) {
      final TextRange range = element.getTextRange();
      addOccurrenceHighlight(editor, range.getStartOffset(), range.getEndOffset(), attributes, flags, outHighlighters, scrollmarkColor);
    }
  }

  public void addOccurrenceHighlight(@NotNull Editor editor,
                                     int start,
                                     int end,
                                     TextAttributes attributes,
                                     int flags,
                                     Collection<RangeHighlighter> outHighlighters,
                                     Color scrollmarkColor) {
    RangeHighlighter highlighter = addSegmentHighlighter(editor, start, end, attributes, flags);
    if (outHighlighters != null) {
      outHighlighters.add(highlighter);
    }
    if (scrollmarkColor != null) {
      highlighter.setErrorStripeMarkColor(scrollmarkColor);
    }
  }

  public void addRangeHighlight(@NotNull Editor editor,
                                int startOffset,
                                int endOffset,
                                @NotNull TextAttributes attributes,
                                boolean hideByTextChange,
                                @Nullable Collection<RangeHighlighter> highlighters) {
    addRangeHighlight(editor, startOffset, endOffset, attributes, hideByTextChange, false, highlighters);
  }

  public void addRangeHighlight(@NotNull Editor editor,
                                int startOffset,
                                int endOffset,
                                @NotNull TextAttributes attributes,
                                boolean hideByTextChange,
                                boolean hideByAnyKey,
                                @Nullable Collection<RangeHighlighter> highlighters) {
    int flags = HIDE_BY_ESCAPE;
    if (hideByTextChange) {
      flags |= HIDE_BY_TEXT_CHANGE;
    }
    if (hideByAnyKey) {
      flags |= HIDE_BY_ANY_KEY;
    }

    Color scrollmarkColor = getScrollMarkColor(attributes);

    addOccurrenceHighlight(editor, startOffset, endOffset, attributes, flags, highlighters, scrollmarkColor);
  }

  public void addOccurrenceHighlights(@NotNull Editor editor,
                                      @NotNull PsiElement[] elements,
                                      @NotNull TextAttributes attributes,
                                      boolean hideByTextChange,
                                      Collection<RangeHighlighter> outHighlighters) {
    if (elements.length == 0) return;
    int flags = HIDE_BY_ESCAPE;
    if (hideByTextChange) {
      flags |= HIDE_BY_TEXT_CHANGE;
    }

    Color scrollmarkColor = getScrollMarkColor(attributes);

    for (PsiElement element : elements) {
      TextRange range = element.getTextRange();
      int start = range.getStartOffset();
      int end = range.getEndOffset();
      addOccurrenceHighlight(editor, start, end, attributes, flags, outHighlighters, scrollmarkColor);
    }
  }

  @Nullable
  private static Color getScrollMarkColor(final TextAttributes attributes) {
    if (attributes.getErrorStripeColor() != null) return attributes.getErrorStripeColor();
    if (attributes.getBackgroundColor() != null) return attributes.getBackgroundColor().darker();
    return null;
  }

  public boolean hideHighlights(Editor editor, int mask) {
    Map<RangeHighlighter, HighlightInfo> map = getHighlightInfoMap(editor, false);
    if (map == null) return false;

    boolean done = false;
    ArrayList<RangeHighlighter> highlightersToRemove = new ArrayList<RangeHighlighter>();
    for (RangeHighlighter highlighter : map.keySet()) {
      HighlightInfo info = map.get(highlighter);
      if (!info.editor.equals(editor)) continue;
      if ((info.flags & mask) != 0) {
        highlightersToRemove.add(highlighter);
        done = true;
      }
    }

    for (RangeHighlighter highlighter : highlightersToRemove) {
      removeSegmentHighlighter(editor, highlighter);
    }

    return done;
  }

  private class MyAnActionListener implements AnActionListener {
    public void beforeActionPerformed(AnAction action, final DataContext dataContext) {
      requestHideHighlights(dataContext);
    }

    public void beforeEditorTyping(char c, DataContext dataContext) {
      requestHideHighlights(dataContext);
    }

    private void requestHideHighlights(final DataContext dataContext) {
      final Editor editor = (Editor)dataContext.getData(DataConstants.EDITOR);
      if (editor == null) return;
      hideHighlights(editor, HIDE_BY_ANY_KEY);
    }
  }
}