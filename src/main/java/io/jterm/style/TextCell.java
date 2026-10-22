package io.jterm.style;

import java.util.EnumSet;

/**
 * Immutable representation of a single terminal cell.
 * Contains the character (as String for multi-codepoint support), foreground color,
 * background color, and SGR modifiers. Safe to share between buffers.
 *
 * @param character the cell's character content (never null or empty)
 * @param fg        foreground color
 * @param bg        background color
 * @param modifiers SGR modifiers applied to this cell (defensively copied)
 */
public record TextCell(String character, Color fg, Color bg, EnumSet<SGR> modifiers) {

    /**
     * Shared empty cell containing a single space with default colors.
     */
    public static final TextCell EMPTY = new TextCell(" ", AnsiColor.DEFAULT, AnsiColor.DEFAULT, EnumSet.noneOf(SGR.class));

    /**
     * Validates the character and defensively copies the modifiers.
     *
     * @throws IllegalArgumentException if the character is null or empty
     */
    public TextCell {
        if (character == null || character.isEmpty())
            throw new IllegalArgumentException("Character cannot be null or empty");
        // Defensive copy of mutable EnumSet
        modifiers = modifiers == null ? EnumSet.noneOf(SGR.class) : EnumSet.copyOf(modifiers);
    }

    // Convenience constructors
    /**
     * Create a cell with the given character and default colors.
     *
     * @param c the character to write
     */
    public TextCell(char c) {
        this(String.valueOf(c), AnsiColor.DEFAULT, AnsiColor.DEFAULT, EnumSet.noneOf(SGR.class));
    }

    /**
     * Create a cell with the given character, colors, and optional modifiers.
     *
     * @param c   the character to write
     * @param fg  the foreground color
     * @param bg  the background color
     * @param mods optional SGR modifiers
     */
    public TextCell(char c, Color fg, Color bg, SGR... mods) {
        this(String.valueOf(c), fg, bg, mods.length == 0 ? EnumSet.noneOf(SGR.class) : EnumSet.of(mods[0], mods));
    }

    // Builder-style copy methods (return new instance since immutable)
    /**
     * Return a copy of this cell with a different character.
     *
     * @param c the new character
     *
     * @return a new TextCell with the given character
     */
    public TextCell withCharacter(char c) {
        return new TextCell(String.valueOf(c), fg, bg, modifiers);
    }

    /**
     * Return a copy of this cell with a different foreground color.
     *
     * @param color the new foreground color
     *
     * @return a new TextCell with the given foreground
     */
    public TextCell withForeground(Color color) {
        return new TextCell(character, color, bg, modifiers);
    }

    /**
     * Return a copy of this cell with a different background color.
     *
     * @param color the new background color
     *
     * @return a new TextCell with the given background
     */
    public TextCell withBackground(Color color) {
        return new TextCell(character, fg, color, modifiers);
    }

    /**
     * Return a copy of this cell with an additional SGR modifier.
     *
     * @param mod the SGR modifier to add
     *
     * @return a new TextCell with the modifier added
     */
    public TextCell withModifier(SGR mod) {
        var mods = EnumSet.copyOf(modifiers);
        mods.add(mod);
        return new TextCell(character, fg, bg, mods);
    }

    /**
     * Return a copy of this cell with an SGR modifier removed.
     *
     * @param mod the SGR modifier to remove
     *
     * @return a new TextCell with the modifier removed
     */
    public TextCell withoutModifier(SGR mod) {
        var mods = EnumSet.copyOf(modifiers);
        mods.remove(mod);
        return new TextCell(character, fg, bg, mods);
    }

    /**
     * Check whether this cell's character matches the given character.
     *
     * @param c the character to compare
     *
     * @return true if this cell holds exactly that character
     */
    public boolean is(char c) {
        return character.length() == 1 && character.charAt(0) == c;
    }

    /**
     * Return whether this cell contains a double-width character.
     *
     * @return true if the character is CJK or multi-codepoint, false otherwise
     */
    public boolean isDoubleWidth() {
        if (character.length() > 1) return true;
        char c = character.charAt(0);
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HANGUL_SYLLABLES
            || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextCell)) return false;
        TextCell other = (TextCell) o;
        return character.equals(other.character)
                && fg == other.fg
                && bg == other.bg
                && modifiers.equals(other.modifiers);
    }

    @Override
    public int hashCode() {
        int result = character.hashCode();
        result = 31 * result + System.identityHashCode(fg);
        result = 31 * result + System.identityHashCode(bg);
        result = 31 * result + modifiers.hashCode();
        return result;
    }
}