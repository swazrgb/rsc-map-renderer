import {createRoot} from "react-dom/client";
import App from "./App";
import "leaflet/dist/leaflet.css";
import "../livemap.css";

const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("root element missing from index.html");

createRoot(rootElement).render(<App />);
