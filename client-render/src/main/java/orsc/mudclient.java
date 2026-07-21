package orsc;

/**
 * Headless stub replacing idlersc's {@code orsc.mudclient} god-object.
 *
 * <p>The ported OpenRSC renderer closure (Scene/World/RSModel/GraphicsController/
 * Shader/EntityHandler/...) references only a handful of static members of the
 * original {@code mudclient}: four sprite-archive base indices, two raw sprite
 * buffers, and {@link #isRender3DEnabled()}. None of the game loop, networking,
 * input, UI or AWT display code is needed to rasterize the world into
 * {@link orsc.graphics.two.GraphicsController#pixelData}, so we satisfy those
 * references with this minimal stand-in instead of vendoring the ~18k-line
 * original.
 *
 * <p>Sprite-base values mirror the constants in the stock client
 * ({@code spriteMedia=2000}, {@code spriteUtil=2100}, {@code spriteItem=2150},
 * {@code spriteProjectile=3160}) so any sprite indexing that does run lands in
 * the same archive slots.
 */
public final class mudclient {

  public static final int spriteMedia = 2000;
  public static final int spriteUtil = 2100;
  public static final int spriteItem = 2150;
  public static final int spriteProjectile = 3160;

  /** Raw sprite colour/width buffers, as in the original client. */
  public static byte[][] s_kb = new byte[250][];
  public static int[] s_wb;

  /** Headless render is always software-3D; never gated off. */
  public static boolean isRender3DEnabled() {
    return true;
  }

  private mudclient() {}
}
