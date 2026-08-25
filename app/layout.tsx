import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "A.R.C. — AI Resource Command",
  description: "Интеллектуальный command center для портфеля проектов, ресурсов, спринтов и релизов.",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
  openGraph: {
    title: "A.R.C. — AI Resource Command",
    description: "Один command center для здоровья проектов, команд и релизов.",
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "A.R.C. Command Center" }],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}
