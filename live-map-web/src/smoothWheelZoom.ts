import L from "leaflet";

declare module "leaflet" {
    interface MapOptions {
        smoothWheelZoom?: boolean | "center";
        smoothSensitivity?: number;
    }
}

interface InternalMap extends L.Map {
    _container: HTMLElement;
    _panAnim?: {stop(): void};
    _stop(): void;
    _moveStart(zoomChanged: boolean, noMoveStart: boolean): void;
    _move(center: L.LatLng, zoom: number): void;
    _moveEnd(zoomChanged: boolean): void;
}

interface SmoothWheelZoom {
    _map: InternalMap;
    _isWheeling: boolean;
    _wheelMousePosition: L.Point;
    _centerPoint: L.Point;
    _startLatLng: L.LatLng;
    _wheelStartLatLng: L.LatLng;
    _startZoom: number;
    _moved: boolean;
    _zooming: boolean;
    _goalZoom: number;
    _prevCenter: L.LatLng;
    _prevZoom: number;
    _zoomAnimationId: number;
    _timeoutId: ReturnType<typeof setTimeout> | undefined;
    _zoom: number;
    _center: L.LatLng;
    addHooks(): void;
    removeHooks(): void;
    _onWheelScroll: (this: SmoothWheelZoom, e: Event) => void;
    _onWheelStart(e: WheelEvent): void;
    _onWheeling(e: WheelEvent): void;
    _onWheelEnd(): void;
    _updateWheelZoom(): void;
}

type SmoothWheelZoomMethods = Pick<
    SmoothWheelZoom,
    "addHooks" | "removeHooks" | "_onWheelScroll" | "_onWheelStart" | "_onWheeling" | "_onWheelEnd" | "_updateWheelZoom"
>;

L.Map.mergeOptions({
    smoothWheelZoom: true,
    smoothSensitivity: 1,
});

const methods: SmoothWheelZoomMethods & ThisType<SmoothWheelZoom> = {
    addHooks() {
        L.DomEvent.on(this._map._container, "wheel", this._onWheelScroll, this);
    },

    removeHooks() {
        L.DomEvent.off(this._map._container, "wheel", this._onWheelScroll, this);
    },

    _onWheelScroll(e: Event) {
        const wheel = e as WheelEvent;
        if (!this._isWheeling) {
            this._onWheelStart(wheel);
        }
        this._onWheeling(wheel);
    },

    _onWheelStart(e: WheelEvent) {
        const map = this._map;
        this._isWheeling = true;
        this._wheelMousePosition = map.mouseEventToContainerPoint(e);
        this._centerPoint = map.getSize().divideBy(2);
        this._startLatLng = map.containerPointToLatLng(this._centerPoint);
        this._wheelStartLatLng = map.containerPointToLatLng(this._wheelMousePosition);
        this._startZoom = map.getZoom();
        this._moved = false;
        this._zooming = true;

        map._stop();
        if (map._panAnim) {
            map._panAnim.stop();
        }

        this._goalZoom = map.getZoom();
        this._prevCenter = map.getCenter();
        this._prevZoom = map.getZoom();

        this._zoomAnimationId = requestAnimationFrame(this._updateWheelZoom.bind(this));
    },

    _onWheeling(e: WheelEvent) {
        const map = this._map;

        let delta = -e.deltaY;
        if (e.deltaMode === 1) delta *= 20;
        else if (e.deltaMode === 2) delta *= map.getSize().y;
        delta = Math.max(-120, Math.min(120, delta));

        const sensitivity = map.options.smoothSensitivity ?? 1;

        const ZOOM_OUT_SPEEDUP = 0.3;
        const speedup = 1 + Math.max(0, map.getMaxZoom() - this._goalZoom) * ZOOM_OUT_SPEEDUP;

        this._goalZoom += delta * 0.0015 * sensitivity * speedup;
        this._goalZoom = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), this._goalZoom));
        this._wheelMousePosition = map.mouseEventToContainerPoint(e);

        clearTimeout(this._timeoutId);
        this._timeoutId = setTimeout(this._onWheelEnd.bind(this), 200);

        L.DomEvent.preventDefault(e);
        L.DomEvent.stopPropagation(e);
    },

    _onWheelEnd() {
        this._isWheeling = false;
        cancelAnimationFrame(this._zoomAnimationId);
        this._map._moveEnd(true);
    },

    _updateWheelZoom() {
        const map = this._map;

        if (!map.getCenter().equals(this._prevCenter) || map.getZoom() !== this._prevZoom) {
            return;
        }

        this._zoom = map.getZoom() + (this._goalZoom - map.getZoom()) * 0.3;
        this._zoom = Math.floor(this._zoom * 100) / 100;

        const delta = this._wheelMousePosition.subtract(this._centerPoint);

        if (map.options.smoothWheelZoom === "center" || (delta.x === 0 && delta.y === 0)) {
            this._center = this._startLatLng;
        } else {
            this._center = map.unproject(map.project(this._wheelStartLatLng, this._zoom).subtract(delta), this._zoom);
        }

        if (!this._moved) {
            map._moveStart(true, false);
            this._moved = true;
        }

        map._move(this._center, this._zoom);
        this._prevCenter = map.getCenter();
        this._prevZoom = map.getZoom();

        this._zoomAnimationId = requestAnimationFrame(this._updateWheelZoom.bind(this));
    },
};

L.Map.addInitHook("addHandler", "smoothWheelZoom", L.Handler.extend(methods));
