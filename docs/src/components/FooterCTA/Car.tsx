import clsx from "clsx";
import { buttonClasses } from "./constants";
import Link from "next/link";

interface CarProps {
  imageHeight: number;
}
export function Car({ imageHeight }: CarProps) {
  return (
    <div style={{ height: imageHeight }} className={clsx(["relative", "z-10"])}>
      <img
        src="/car.webp"
        className={clsx(["left-0", "right-0", "absolute", "top-0", "m-auto"])}
      />
      <span
        className={clsx([
          "absolute",
          "bottom-0",
          "wide-phone:bottom-1",
          "tablet:bottom-1",
          "desktop:bottom-4",
          "right-0",
          "text-sm",
          "wide-phone:text-base",
          "tablet:text-lg",
          "text-center",
          "right-[10px]",
          "tablet:right-[20px]",
          "desktop:right-[110px]",
        ])}
      >
        let your codebase take its own selfies
      </span>
      <Link
        href="/jvm/get-started"
        className={clsx([
          buttonClasses,
          "absolute",
          "bottom-[30px]",
          "wide-phone:bottom-[60px]",
          "tablet:bottom-[75px]",
          "desktop:bottom-[150px]",
          "w-[130px]",
          "wide-phone:w-[180px]",
          "tablet:w-[210px]",
          "left-0",
          "right-0",
          "m-auto",
        ])}
      >
        get started
      </Link>
    </div>
  );
}
