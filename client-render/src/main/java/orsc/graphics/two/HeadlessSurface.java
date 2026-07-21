package orsc.graphics.two;

/**
 * Headless concrete {@link GraphicsController}.
 *
 * <p>{@code GraphicsController}'s constructor is package-private and the stock
 * client only ever instantiated it through {@code MudClientGraphics} (which
 * delegated entity billboards back to the {@code mudclient} god-object). We
 * dropped that subclass, so this thin stand-in re-exposes the constructor for
 * the headless world renderer. {@code drawEntity} is concrete on the base class
 * and is never invoked for terrain/scenery rasterization, so nothing else is
 * needed.
 */
public final class HeadlessSurface extends GraphicsController {

  public HeadlessSurface(int width, int height, int spriteCount) {
    super(width, height, spriteCount);
  }
}
